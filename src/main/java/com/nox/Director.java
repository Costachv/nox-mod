package com.nox;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Random;

/** O "diretor de terror": decide sozinho, com base na raiva, quando assustar. */
public class Director {
    private static final Random R = new Random();
    private static int tick = 0;
    private static int metaCooldown = 0;

    public static void tick(MinecraftServer s) {
        tick++;
        if (metaCooldown > 0) metaCooldown--;
        if (tick % 3600 == 0) NoxState.addAnger(-1, null); // raiva esfria devagar (3 min por ponto)
        if (tick % 1200 == 0) NoxState.save();

        if (s.getPlayerManager().getPlayerList().isEmpty()) return;
        ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);

        if (tick % 3000 == 0) NoxBrain.think(p, null); // fala/age por conta própria a cada ~2,5 min

        if (tick % 200 == 0 && NoxState.anger >= 50) {
            double chance = (NoxState.anger - 40) / 100.0 * 0.6; // 50 -> 6%, 100 -> 36% a cada 10s
            if (R.nextDouble() < chance) scare(s, p);
        }
    }

    public static void scare(MinecraftServer s, ServerPlayerEntity p) {
        int roll = R.nextInt(10);
        if (roll < 2) stare(p);
        else if (roll < 4) appearBehind(p);
        else if (roll < 6) buildWeird(p);
        else if (roll < 9) creepySound(p);
        else if (NoxState.anger >= 85 && NoxConfig.INSTANCE.metaHorror && metaCooldown == 0) {
            metaCooldown = 6000; // no máx. 1 coisa "fora do jogo" a cada 5 min
            Bridge.Q.add(R.nextBoolean() ? "NOTE" : "GLITCH");
        }
    }

    /** Barulhos atrás do jogador: porta, baú, enderman, zumbi... */
    public static void creepySound(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        SoundEvent[] pool = {
                SoundEvents.ENTITY_ENDERMAN_STARE, SoundEvents.BLOCK_WOODEN_DOOR_OPEN,
                SoundEvents.BLOCK_CHEST_OPEN, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundEvents.ENTITY_ZOMBIE_AMBIENT
        };
        Vec3d pos = p.getPos().add(Vec3d.fromPolar(0, p.getYaw() + 180 + R.nextInt(90) - 45).multiply(6 + R.nextInt(6)));
        w.playSound(null, pos.x, pos.y, pos.z, pool[R.nextInt(pool.length)], SoundCategory.AMBIENT, 1.0f, 0.7f + R.nextFloat() * 0.4f);
    }

    public static NoxEntity spawnNear(ServerPlayerEntity p) {
        Vec3d dir = Vec3d.fromPolar(0, p.getYaw());
        Vec3d pos = p.getPos().add(dir.multiply(3));
        return spawn(p.getServerWorld(), pos.x, pos.y, pos.z);
    }

    static NoxEntity spawn(ServerWorld w, double x, double y, double z) {
        NoxEntity e = NoxMod.NOX.create(w);
        if (e == null) return null;
        e.refreshPositionAndAngles(x, y, z, 0, 0);
        e.setCustomName(Text.literal(NoxConfig.INSTANCE.botName));
        e.setCustomNameVisible(true);
        w.spawnEntity(e);
        return e;
    }

    public static void appearBehind(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d back = Vec3d.fromPolar(0, p.getYaw() + 180);
        Vec3d pos = p.getPos().add(back.multiply(5));
        NoxEntity e = spawn(w, pos.x, p.getY(), pos.z);
        if (e != null) { e.mode = NoxEntity.Mode.STAY; e.vanishAfter = 70; }
    }

    public static void stare(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        double a = R.nextDouble() * Math.PI * 2;
        int dist = 30 + R.nextInt(15);
        int x = (int) (p.getX() + Math.cos(a) * dist), z = (int) (p.getZ() + Math.sin(a) * dist);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        NoxEntity e = spawn(w, x + 0.5, y, z + 0.5);
        if (e != null) { e.mode = NoxEntity.Mode.STAY; e.vanishWhenSeen = true; e.vanishAfter = 1200; }
    }

    /** Construções estranhas, procedurais, longe do jogador. */
    public static void buildWeird(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        if (w.getRegistryKey() != World.OVERWORLD) return;
        double a = R.nextDouble() * Math.PI * 2;
        int dist = 18 + R.nextInt(14);
        int x = (int) (p.getX() + Math.cos(a) * dist), z = (int) (p.getZ() + Math.sin(a) * dist);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);

        switch (R.nextInt(3)) {
            case 0 -> { // pilar com tocha de alma no topo
                int h = 6 + R.nextInt(8);
                for (int i = 0; i < h; i++) set(w, x, y + i, z, Blocks.POLISHED_BLACKSTONE);
                set(w, x, y + h, z, Blocks.SOUL_TORCH);
            }
            case 1 -> { // escada pra lugar nenhum
                int n = 8 + R.nextInt(6);
                for (int i = 0; i < n; i++) set(w, x + i, y + i, z, Blocks.COBBLESTONE);
            }
            default -> { // cela oca com uma lanterna dentro
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = 0; dy <= 3; dy++)
                        for (int dz = -1; dz <= 1; dz++) {
                            boolean shell = Math.abs(dx) == 1 || Math.abs(dz) == 1 || dy == 3;
                            set(w, x + dx, y + dy, z + dz, shell ? Blocks.MOSSY_COBBLESTONE : Blocks.AIR);
                        }
                set(w, x, y, z, Blocks.SOUL_LANTERN);
                set(w, x, y + 1, z + 1, Blocks.IRON_BARS); // uma "janela"
            }
        }
    }

    private static void set(ServerWorld w, int x, int y, int z, Block b) {
        w.setBlockState(new BlockPos(x, y, z), b.getDefaultState());
    }
}
