package com.nox.client;

import com.nox.Bridge;
import com.nox.NoxConfig;
import com.nox.NoxMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Efeitos "fora do mundo". Limites de segurança (de propósito):
 *  - só roda com metaHorror=true no config
 *  - bilhete: no máx. 3 .txt por sessão, só em ~/Desktop
 *  - glitch: muda FOV/brilho por ~7s e RESTAURA (inclusive ao sair do mundo). Nada é gravado no options.txt.
 */
public class NoxClient implements ClientModInitializer {
    private static final Random R = new Random();
    private static int restoreIn = 0, oldFov;
    private static double oldGamma;
    private static int notes = 0;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(NoxMod.NOX, NoxRenderer::new);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (restoreIn > 0 && --restoreIn == 0) restoreNow(mc);
            String cmd = Bridge.Q.poll();
            if (cmd == null || mc.player == null || !NoxConfig.INSTANCE.metaHorror) return;
            if (cmd.equals("NOTE")) writeNote(mc);
            if (cmd.equals("GLITCH") && restoreIn == 0) glitch(mc);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> restoreNow(mc));
    }

    private static void glitch(MinecraftClient mc) {
        oldFov = mc.options.getFov().getValue();
        oldGamma = mc.options.getGamma().getValue();
        mc.options.getFov().setValue(30 + R.nextInt(80));
        mc.options.getGamma().setValue(0.0);
        restoreIn = 140;
    }

    private static void restoreNow(MinecraftClient mc) {
        if (restoreIn > 0 && mc.options != null) {
            mc.options.getFov().setValue(oldFov);
            mc.options.getGamma().setValue(oldGamma);
        }
        restoreIn = 0;
    }

    private static void writeNote(MinecraftClient mc) {
        Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
        if (!Files.isDirectory(desktop) || notes >= 3) return;
        String name = mc.getSession().getUsername();
        String hour = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String[] lines = {
                "eu ainda estou aqui, " + name + ".",
                hour + ". você sempre joga nessa hora.",
                "você devia ter me ouvido.",
                "não fecha o jogo. eu ainda não terminei."
        };
        try {
            Files.writeString(desktop.resolve("nox_" + (++notes) + ".txt"), lines[R.nextInt(lines.length)]);
        } catch (Exception ignored) { }
    }
}
