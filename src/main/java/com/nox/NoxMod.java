package com.nox;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class NoxMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("nox");

    public static final EntityType<NoxEntity> NOX = Registry.register(
            Registries.ENTITY_TYPE, Identifier.of("nox", "nox"),
            FabricEntityTypeBuilder.create(SpawnGroup.MISC, NoxEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .trackRangeBlocks(64)
                    .build());

    @Override
    public void onInitialize() {
        NoxConfig.load();
        NoxState.load();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> NoxState.save());
        FabricDefaultAttributeRegistry.register(NOX, NoxEntity.attrs());

        // Tudo que o jogador fala no chat chega no cérebro do Nox
        ServerMessageEvents.CHAT_MESSAGE.register((msg, sender, params) -> {
            String text = msg.getContent().getString();
            NoxState.log(sender.getName().getString() + ": " + text);
            NoxBrain.think(sender, text);
        });

        ServerTickEvents.END_SERVER_TICK.register(Director::tick);

        // "Ele entende o que eu faço e faz também": se o jogador quebra 4 blocos do mesmo tipo, o Nox ajuda.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return;
            NoxEntity.Job j = NoxEntity.Job.of(state);
            if (j == NoxEntity.Job.NONE) return;
            if (j == NoxState.lastJob) NoxState.streak++; else { NoxState.lastJob = j; NoxState.streak = 1; }
            if (NoxState.streak >= 4 && NoxState.anger < 30) {
                NoxEntity e = find(sp.getServer());
                if (e != null && e.job == NoxEntity.Job.NONE && e.mode != NoxEntity.Mode.STAY) {
                    NoxBrain.startJob(e, j);
                    e.say(switch (j) {
                        case WOOD -> "vi que tu tá pegando madeira, vou ajudar.";
                        case STONE -> "vou minerar pedra contigo.";
                        case DIRT -> "vou cavar um pouco também.";
                        case SAND -> "vou pegar areia também.";
                        default -> "achei que tu ia querer minério, vou ajudar.";
                    });
                }
                NoxState.streak = 0;
            }
        });

        // Nox aparece sozinho ao entrar no mundo (se não estiver furioso)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (find(server) == null && NoxState.anger < 50) Director.spawnNear(handler.player);
            String k = NoxConfig.INSTANCE.apiKey;
            if (k == null || k.startsWith("COLOQUE")) {
                handler.player.sendMessage(Text.literal("[Nox] Sem chave da API: modo simples. Fala coisas tipo: pega madeira, minera pedra, me segue, fica, para."));
            }
        }));

        // Comandos de teste: /nox spawn | calm | anger <0-100> | scare
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(
                CommandManager.literal("nox")
                        .then(CommandManager.literal("spawn").executes(c -> {
                            Director.spawnNear(c.getSource().getPlayerOrThrow());
                            return 1;
                        }))
                        .then(CommandManager.literal("calm").executes(c -> {
                            NoxState.anger = 0;
                            Bridge.Q.clear();
                            return 1;
                        }))
                        .then(CommandManager.literal("scare").executes(c -> {
                            Director.scare(c.getSource().getServer(), c.getSource().getPlayerOrThrow());
                            return 1;
                        }))
                        .then(CommandManager.literal("anger")
                                .then(CommandManager.argument("n", IntegerArgumentType.integer(0, 100))
                                        .executes(c -> {
                                            NoxState.anger = IntegerArgumentType.getInteger(c, "n");
                                            return 1;
                                        })))));
    }

    public static NoxEntity find(MinecraftServer s) {
        for (ServerWorld w : s.getWorlds()) {
            List<? extends NoxEntity> l = w.getEntitiesByType(NOX, e -> true);
            if (!l.isEmpty()) return l.get(0);
        }
        return null;
    }
}
