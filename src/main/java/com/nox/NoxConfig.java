package com.nox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Config em config/nox.json (criada automaticamente no primeiro start). */
public class NoxConfig {
    public String apiKey = "COLOQUE_SUA_CHAVE_AQUI";
    public String model = "claude-haiku-4-5-20251001";
    public String botName = "Nox_7";
    /** Se true, o Nox pode fazer coisas FORA do mundo (txt na área de trabalho, glitch de FOV/brilho). */
    public boolean metaHorror = false;

    public static NoxConfig INSTANCE = new NoxConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("nox.json");
    }

    public static void load() {
        try {
            if (Files.exists(path())) {
                INSTANCE = GSON.fromJson(Files.readString(path()), NoxConfig.class);
            } else {
                Files.writeString(path(), GSON.toJson(INSTANCE));
            }
        } catch (Exception e) {
            NoxMod.LOG.error("Erro lendo config do Nox", e);
        }
    }
}
