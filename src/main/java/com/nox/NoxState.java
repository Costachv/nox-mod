package com.nox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Estado emocional e memória curta do Nox (fica em memória; reinicia ao fechar o jogo). */
public class NoxState {
    public static int anger = 0; // 0..100
    public static NoxEntity.Job lastJob = NoxEntity.Job.NONE; // último recurso que o jogador coletou
    public static int streak = 0;
    public static final Deque<String> memory = new ArrayDeque<>();
    public static final Deque<String> chat = new ArrayDeque<>();

    public static void addAnger(int delta, String why) {
        anger = Math.max(0, Math.min(100, anger + delta));
        if (why != null) remember(why);
    }

    public static void remember(String s) {
        memory.addLast(s);
        while (memory.size() > 10) memory.removeFirst();
    }

    public static void log(String line) {
        chat.addLast(line);
        while (chat.size() > 14) chat.removeFirst();
    }

    // ---- persistência (config/nox_state.json): a raiva e a memória sobrevivem ao fechar o jogo ----
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static class Data {
        int anger;
        List<String> memory = new ArrayList<>();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nox_state.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Data d = GSON.fromJson(Files.readString(file()), Data.class);
                anger = d.anger;
                memory.clear();
                memory.addAll(d.memory);
            }
        } catch (Exception ignored) { }
    }

    public static void save() {
        try {
            Data d = new Data();
            d.anger = anger;
            d.memory.addAll(memory);
            Files.writeString(file(), GSON.toJson(d));
        } catch (Exception ignored) { }
    }
}
