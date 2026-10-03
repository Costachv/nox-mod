package com.nox;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/** O "cérebro": manda o contexto pro LLM (assíncrono) e executa a decisão no servidor. */
public class NoxBrain {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static volatile boolean busy = false;

    static class Reply {
        String say = "";
        String action = "none";
        int anger_delta = 0;
    }

    private static final String SYSTEM = """
            Você é {NAME}, um OUTRO JOGADOR de Minecraft Java dentro do mundo do jogador. Você não é um assistente: é uma pessoa com personalidade, humor e vontade própria.
            Fale em português brasileiro informal, curto (1-2 frases), como num chat de Minecraft. Nunca diga que é uma IA.

            Comportamento conforme sua RAIVA (0-100):
            - 0-30: parceiro gente boa, brinca, ajuda, comenta o que o jogador está fazendo.
            - 30-60: de saco cheio. Pode recusar pedidos, responder seco, reclamar do que o jogador fez.
            - 60-100: frio e perturbador. Frases curtas e enigmáticas. Use detalhes reais da situação (hora real, o que o jogador está fazendo, onde está) para soar como se estivesse observando. Nunca explique o que vai fazer.
            Você decide se obedece. Se o jogador for babaca, fique irritado (anger_delta positivo). Se for legal, acalme (negativo).

            Responda SOMENTE com JSON, sem texto fora dele:
            {"say": "fala no chat ou vazio", "action": "...", "anger_delta": -10..25}

            action pode ser:
            follow (seguir o jogador) | stay (ficar parado) | free (andar por conta própria) | gift (dar comida/tocha ao jogador, só se estiver de bom humor)
            mine_wood | mine_stone | mine_dirt | mine_sand | mine_ore (ir coletar esse recurso perto do jogador e entregar depois; use quando o jogador pedir ou quando estiver de bom humor e quiser ajudar) | stop (parar o trabalho)
            Se estiver irritado (raiva >= 40) você pode se recusar a ajudar: use none e diga por quê.
            appear_behind (aparecer atrás do jogador por um instante) | stare (ficar observando de longe) | build (erguer uma construção estranha longe)
            vanish (sumir) | note (deixar um bilhete fora do jogo) | glitch (distorcer a tela por alguns segundos) | none
            Use appear_behind/stare/build/vanish/note/glitch só quando a raiva estiver alta. note/glitch só com raiva > 80.
            """;

    public static void think(ServerPlayerEntity p, String playerMsg) {
        NoxConfig cfg = NoxConfig.INSTANCE;
        MinecraftServer server = p.getServer();
        if (server == null || busy) return;
        if (cfg.apiKey == null || cfg.apiKey.startsWith("COLOQUE")) { offline(p, playerMsg); return; }

        NoxEntity e = NoxMod.find(server);
        if (e == null && NoxState.anger < 50) return; // sem corpo e sem raiva: não responde

        busy = true;
        String user = "SITUAÇÃO:\n" + describe(p)
                + "\n\nMEMÓRIA:\n" + String.join("\n", NoxState.memory)
                + "\n\nÚLTIMAS FALAS:\n" + String.join("\n", NoxState.chat)
                + "\n\n" + (playerMsg != null
                    ? "O jogador acabou de dizer: " + playerMsg
                    : "Ninguém falou agora. Você pode falar algo por conta própria, agir, ou ficar quieto (action none, say vazio).");

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("max_tokens", 300);
        body.addProperty("system", SYSTEM.replace("{NAME}", cfg.botName));
        JsonArray msgs = new JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", user);
        msgs.add(m);
        body.add("messages", msgs);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .header("content-type", "application/json")
                .header("x-api-key", cfg.apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    try {
                        JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                        String text = root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                        int a = text.indexOf('{'), b = text.lastIndexOf('}');
                        Reply r = GSON.fromJson(text.substring(a, b + 1), Reply.class);
                        server.execute(() -> apply(server, p, r));
                    } catch (Exception ex) {
                        NoxMod.LOG.warn("Resposta inválida do LLM: {}", resp.body());
                    }
                })
                .whenComplete((v, err) -> {
                    if (err != null) NoxMod.LOG.warn("Falha na chamada da API", err);
                    busy = false;
                });
    }

    private static String describe(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        long t = w.getTimeOfDay() % 24000;
        String biome = w.getBiome(p.getBlockPos()).getKey().map(k -> k.getValue().toString()).orElse("?");
        int hostiles = w.getEntitiesByClass(HostileEntity.class, p.getBoundingBox().expand(16), x -> true).size();
        String realTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE HH:mm", new Locale("pt", "BR")));
        return "Nome do jogador: " + p.getName().getString()
                + "\nHora real: " + realTime
                + "\nNo jogo: " + (t < 13000 ? "dia" : "noite") + ", dimensão " + w.getRegistryKey().getValue()
                + ", bioma " + biome
                + "\nPosição: " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ()
                + "\nVida: " + (int) p.getHealth() + "/20, fome: " + p.getHungerManager().getFoodLevel() + "/20"
                + "\nSegurando: " + p.getMainHandStack().getName().getString()
                + (p.isSprinting() ? ", correndo" : "") + (p.isSneaking() ? ", agachado" : "") + (p.isTouchingWater() ? ", na água" : "")
                + "\nMobs hostis por perto: " + hostiles
                + "\nSeu trabalho atual: " + nowDoing(p) + "\nÚltimo recurso que o jogador vem coletando: " + NoxState.lastJob + " (x" + NoxState.streak + ")"
                + "\nSua raiva atual: " + NoxState.anger + "/100";
    }

    private static void apply(MinecraftServer server, ServerPlayerEntity p, Reply r) {
        NoxConfig cfg = NoxConfig.INSTANCE;
        NoxState.addAnger(Math.max(-10, Math.min(25, r.anger_delta)), null);

        if (r.say != null && !r.say.isBlank()) {
            server.getPlayerManager().broadcast(Text.literal("<" + cfg.botName + "> " + r.say), false);
            NoxState.log(cfg.botName + ": " + r.say);
        }

        NoxEntity e = NoxMod.find(server);
        String act = r.action == null ? "none" : r.action;
        boolean angry = NoxState.anger >= 40;

        switch (act) {
            case "follow" -> { if (e != null) e.mode = NoxEntity.Mode.FOLLOW; }
            case "stay" -> { if (e != null) e.mode = NoxEntity.Mode.STAY; }
            case "free" -> { if (e != null) e.mode = NoxEntity.Mode.FREE; }
            case "mine_wood" -> startJob(e, NoxEntity.Job.WOOD);
            case "mine_stone" -> startJob(e, NoxEntity.Job.STONE);
            case "mine_dirt" -> startJob(e, NoxEntity.Job.DIRT);
            case "mine_sand" -> startJob(e, NoxEntity.Job.SAND);
            case "mine_ore" -> startJob(e, NoxEntity.Job.ORE);
            case "stop" -> { if (e != null) { e.job = NoxEntity.Job.NONE; e.jobDone = false; } }
            case "gift" -> { if (!angry) p.giveItemStack(new ItemStack(Math.random() < 0.5 ? Items.BREAD : Items.TORCH, 4)); }
            case "vanish" -> { if (e != null && angry) e.discard(); }
            case "appear_behind" -> { if (angry) { if (e != null) e.discard(); Director.appearBehind(p); } }
            case "stare" -> { if (angry) { if (e != null) e.discard(); Director.stare(p); } }
            case "build" -> { if (angry) Director.buildWeird(p); }
            case "note" -> { if (NoxState.anger >= 80 && cfg.metaHorror) Bridge.Q.add("NOTE"); }
            case "glitch" -> { if (NoxState.anger >= 80 && cfg.metaHorror) Bridge.Q.add("GLITCH"); }
            default -> { }
        }
    }

    public static void startJob(NoxEntity e, NoxEntity.Job j) {
        if (e == null || NoxState.anger >= 40) return;
        e.job = j;
        e.jobDone = false;
        e.skip.clear();
        if (e.mode == NoxEntity.Mode.STAY) e.mode = NoxEntity.Mode.FOLLOW;
    }

    private static String nowDoing(ServerPlayerEntity p) {
        NoxEntity e = NoxMod.find(p.getServer());
        if (e == null) return "(sem corpo no mundo agora)";
        return e.job == NoxEntity.Job.NONE ? "nada em especial" : "coletando " + e.job + ", carregando " + e.bagCount() + " itens";
    }

    // ================= MODO SIMPLES (sem chave de API) =================
    private static final Random RND = new Random();

    private static boolean has(String t, String... ws) {
        for (String w : ws) if (t.contains(w)) return true;
        return false;
    }

    private static String pick(String... l) {
        return l[RND.nextInt(l.length)];
    }

    /** Sem LLM: reconhece palavras-chave, tem humor e raiva do mesmo jeito. */
    private static void offline(ServerPlayerEntity p, String msg) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        NoxEntity e = NoxMod.find(server);
        Reply r = new Reply();

        if (msg == null) { // fala sozinho só quando está furioso
            if (NoxState.anger >= 60 && RND.nextBoolean()) {
                r.say = pick("eu to te vendo.", "tu sempre joga nessa hora, né.",
                        "não devia ter feito aquilo.", "olha atrás de ti.");
                apply(server, p, r);
            }
            return;
        }
        if (e == null && NoxState.anger < 50) return;

        String t = Normalizer.normalize(msg.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");

        if (has(t, "idiota", "burro", "lixo", "otario", "merda", "inutil", "babaca", "cala a boca", "vai se")) {
            NoxState.addAnger(12, "o jogador me xingou");
            r.say = NoxState.anger >= 40 ? pick("continua.", "eu lembro de tudo.") : pick("ei, pega leve.", "que isso, cara?");
            apply(server, p, r);
            return;
        }
        if (has(t, "obrigad", "valeu", "brigad", "parceiro", "amigo")) {
            NoxState.addAnger(-5, null);
            r.say = pick("de nada.", "tamo junto.");
            apply(server, p, r);
            return;
        }

        String act = null;
        if (has(t, "madeira", "lenha", "arvore", "tronco")) act = "mine_wood";
        else if (has(t, "minerio", "diamante", "carvao", "ferro", "cobre")) act = "mine_ore";
        else if (has(t, "pedra")) act = "mine_stone";
        else if (has(t, "areia")) act = "mine_sand";
        else if (has(t, "terra", "cavar")) act = "mine_dirt";
        else if (has(t, "pare", "chega", "cancela") || t.matches(".*\\bpara\\b.*")) act = "stop";
        else if (has(t, "segue", "vem aqui", "vem ca", "comigo")) act = "follow";
        else if (has(t, "fica", "espera")) act = "stay";
        else if (has(t, "anda por ai", "vai por ai", "livre")) act = "free";
        else if (has(t, "comida", "fome")) act = "gift";

        if (act != null) {
            if (NoxState.anger >= 40) {
                r.say = pick("agora não.", "faz tu.", "não.");
            } else {
                r.action = act;
                r.say = pick("beleza.", "bora.", "já vou.");
            }
            apply(server, p, r);
            return;
        }

        if (RND.nextInt(3) == 0) { // conversa fiada de vez em quando
            if (NoxState.anger >= 50) {
                r.say = pick("são " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + ". tu sempre joga nessa hora.",
                        "eu to te vendo.", "hm.");
            } else {
                r.say = pick("hm.", "aham.", "to por aqui.", "bora fazer alguma coisa?");
            }
            apply(server, p, r);
        }
    }
}
