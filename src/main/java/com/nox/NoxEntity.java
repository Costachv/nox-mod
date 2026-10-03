package com.nox;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.*;

public class NoxEntity extends PathAwareEntity {
    public enum Mode { FOLLOW, STAY, FREE }

    /** Trabalhos que o Nox sabe fazer. */
    public enum Job {
        NONE, WOOD, STONE, DIRT, SAND, ORE;

        public boolean matches(BlockState s) {
            return switch (this) {
                case WOOD -> s.isIn(BlockTags.LOGS);
                case STONE -> s.isIn(BlockTags.BASE_STONE_OVERWORLD);
                case DIRT -> s.isIn(BlockTags.DIRT);
                case SAND -> s.isIn(BlockTags.SAND);
                case ORE -> s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.COPPER_ORES);
                default -> false;
            };
        }

        public static Job of(BlockState s) {
            for (Job j : values()) if (j != NONE && j.matches(s)) return j;
            return NONE;
        }

        public Item tool() {
            return switch (this) {
                case WOOD -> Items.IRON_AXE;
                case STONE, ORE -> Items.IRON_PICKAXE;
                case DIRT, SAND -> Items.IRON_SHOVEL;
                default -> Items.AIR;
            };
        }
    }

    public Mode mode = Mode.FOLLOW;
    public Job job = Job.NONE;
    public boolean jobDone = false;
    public final List<ItemStack> bag = new ArrayList<>();
    public final Set<BlockPos> skip = new HashSet<>();
    public int vanishAfter = 0;
    public boolean vanishWhenSeen = false;

    public NoxEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setPersistent();
        setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
    }

    public static DefaultAttributeContainer.Builder attrs() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48);
    }

    public int bagCount() {
        int n = 0;
        for (ItemStack s : bag) n += s.getCount();
        return n;
    }

    public void say(String msg) {
        if (getWorld() instanceof ServerWorld sw) {
            String n = NoxConfig.INSTANCE.botName;
            sw.getServer().getPlayerManager().broadcast(Text.literal("<" + n + "> " + msg), false);
            NoxState.log(n + ": " + msg);
        }
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new MeleeAttackGoal(this, 1.2, true));
        goalSelector.add(2, new DeliverGoal(this));
        goalSelector.add(3, new MineGoal(this));
        goalSelector.add(4, new FollowGoal(this));
        goalSelector.add(5, new WanderAroundFarGoal(this, 0.8) {
            @Override public boolean canStart() { return mode == Mode.FREE && super.canStart(); }
        });
        goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8f));
        goalSelector.add(7, new LookAroundGoal(this));

        // Defende o jogador de mobs hostis (só se não estiver irritado)
        targetSelector.add(1, new ActiveTargetGoal<HostileEntity>(this, HostileEntity.class, true) {
            @Override public boolean canStart() {
                return NoxState.anger < 40 && mode != Mode.STAY && vanishAfter == 0 && super.canStart();
            }
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;

        if (vanishAfter > 0 && --vanishAfter == 0) { discard(); return; }

        if (vanishWhenSeen) {
            PlayerEntity p = getWorld().getClosestPlayer(this, 80);
            if (p != null) {
                getLookControl().lookAt(p, 30, 30);
                Vec3d toMe = getEyePos().subtract(p.getEyePos()).normalize();
                if (p.getRotationVec(1f).dotProduct(toMe) > 0.97) discard();
            }
        }

        // não persegue mob longe demais do jogador
        if (getTarget() != null && age % 20 == 0) {
            PlayerEntity p = getWorld().getClosestPlayer(this, 48);
            if (p == null || p.squaredDistanceTo(getTarget()) > 400) setTarget(null);
        }

        placeTorchIfDark();

        // ferramenta na mão conforme o que está fazendo
        if (age % 10 == 0) {
            Item want = getTarget() != null ? Items.IRON_SWORD : job.tool();
            if (!getMainHandStack().isOf(want)) {
                setStackInHand(Hand.MAIN_HAND, want == Items.AIR ? ItemStack.EMPTY : new ItemStack(want));
            }
        }
    }

    private int torchCd = 0;

    /** Quando está escuro, o Nox coloca tochas pelo caminho (bom humor apenas). */
    private void placeTorchIfDark() {
        if (torchCd > 0) { torchCd--; return; }
        if (age % 20 != 0 || NoxState.anger >= 40 || mode == Mode.STAY || vanishAfter > 0) return;
        World w = getWorld();
        BlockPos pos = getBlockPos();
        if (w.getLightLevel(pos) < 7 && w.getLightLevel(LightType.BLOCK, pos) < 4
                && w.getBlockState(pos).isAir()
                && w.getBlockState(pos.down()).isSideSolidFullSquare(w, pos.down(), Direction.UP)) {
            w.setBlockState(pos, Blocks.TORCH.getDefaultState());
            torchCd = 100;
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.getAttacker() instanceof PlayerEntity && !getWorld().isClient) {
            NoxState.addAnger(8, "o jogador me bateu");
        }
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!getWorld().isClient && source.getAttacker() instanceof PlayerEntity) {
            NoxState.addAnger(25, "o jogador me matou");
        }
        super.onDeath(source);
    }

    /** Bloco do trabalho atual mais perto dele (e não muito longe do jogador). */
    BlockPos findBlock() {
        World w = getWorld();
        PlayerEntity p = w.getClosestPlayer(this, 64);
        if (p == null) return null;
        BlockPos o = getBlockPos();
        BlockPos best = null;
        double bd = 1e9;
        for (int dx = -12; dx <= 12; dx++)
            for (int dz = -12; dz <= 12; dz++)
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos b = o.add(dx, dy, dz);
                    if (skip.contains(b)) continue;
                    double dp = Vec3d.ofCenter(b).squaredDistanceTo(p.getPos());
                    if (dp > 576 || dp < 4) continue; // longe demais do jogador / debaixo dos pés dele
                    if (!job.matches(w.getBlockState(b))) continue;
                    double d = Vec3d.ofCenter(b).squaredDistanceTo(getPos());
                    if (d < bd) { bd = d; best = b; }
                }
        return best;
    }

    static class FollowGoal extends Goal {
        final NoxEntity e;
        PlayerEntity target;

        FollowGoal(NoxEntity e) {
            this.e = e;
            setControls(EnumSet.of(Goal.Control.MOVE));
        }

        @Override public boolean canStart() {
            if (e.mode != Mode.FOLLOW) return false;
            target = e.getWorld().getClosestPlayer(e, 48);
            return target != null && e.squaredDistanceTo(target) > 25;
        }

        @Override public boolean shouldContinue() {
            return e.mode == Mode.FOLLOW && target != null && target.isAlive()
                    && e.squaredDistanceTo(target) > 9;
        }

        @Override public void tick() {
            e.getNavigation().startMovingTo(target, 1.15);
            if (e.squaredDistanceTo(target) > 1024) {
                e.refreshPositionAndAngles(target.getX(), target.getY(), target.getZ(), e.getYaw(), e.getPitch());
            }
        }
    }

    static class MineGoal extends Goal {
        final NoxEntity e;
        BlockPos target;
        int progress, stuck, scanCd;

        MineGoal(NoxEntity e) {
            this.e = e;
            setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
        }

        @Override public boolean canStart() {
            if (e.job == Job.NONE || e.mode == Mode.STAY || e.bagCount() >= 32) return false;
            if (scanCd > 0) { scanCd--; return false; }
            target = e.findBlock();
            if (target == null) {
                scanCd = 40;
                e.jobDone = true;
                if (e.bag.isEmpty()) {
                    e.say("não achei mais nada por aqui.");
                    e.job = Job.NONE;
                    e.jobDone = false;
                }
                return false;
            }
            e.jobDone = false;
            return true;
        }

        @Override public boolean shouldContinue() {
            return target != null && e.job != Job.NONE && e.mode != Mode.STAY
                    && e.bagCount() < 32 && e.job.matches(e.getWorld().getBlockState(target));
        }

        @Override public void start() { progress = 0; stuck = 0; }

        @Override public void stop() {
            target = null;
            e.getNavigation().stop();
        }

        @Override public void tick() {
            if (target == null) return;
            Vec3d c = Vec3d.ofCenter(target);
            e.getLookControl().lookAt(c.x, c.y, c.z);

            if (e.squaredDistanceTo(c) > 9) { // anda até o bloco
                if (e.getNavigation().isIdle()) e.getNavigation().startMovingTo(c.x, target.getY(), c.z, 1.1);
                if (++stuck > 200) { e.skip.add(target); target = null; } // inalcançável: desiste desse bloco
                return;
            }

            stuck = 0;
            e.getNavigation().stop();
            e.swingHand(Hand.MAIN_HAND);
            BlockState st = e.getWorld().getBlockState(target);
            int need = (int) Math.max(10, st.getHardness(e.getWorld(), target) * 30);
            if (++progress >= need && e.getWorld() instanceof ServerWorld sw) {
                List<ItemStack> drops = Block.getDroppedStacks(st, sw, target, sw.getBlockEntity(target), e, e.getMainHandStack());
                sw.breakBlock(target, false, e);
                e.bag.addAll(drops);
                progress = 0;
            }
        }
    }

    static class DeliverGoal extends Goal {
        final NoxEntity e;
        PlayerEntity target;

        DeliverGoal(NoxEntity e) {
            this.e = e;
            setControls(EnumSet.of(Goal.Control.MOVE));
        }

        @Override public boolean canStart() {
            if (e.bag.isEmpty()) return false;
            if (!(e.bagCount() >= 32 || e.jobDone || e.job == Job.NONE)) return false;
            target = e.getWorld().getClosestPlayer(e, 64);
            return target != null;
        }

        @Override public boolean shouldContinue() {
            return !e.bag.isEmpty() && target != null && target.isAlive();
        }

        @Override public void tick() {
            e.getLookControl().lookAt(target, 30, 30);
            if (e.squaredDistanceTo(target) > 9) {
                e.getNavigation().startMovingTo(target, 1.2);
                return;
            }
            if (e.getWorld() instanceof ServerWorld sw) {
                Vec3d v = target.getPos().subtract(e.getPos()).normalize().multiply(0.25);
                for (ItemStack s : e.bag) {
                    ItemEntity ie = new ItemEntity(sw, e.getX(), e.getEyeY() - 0.3, e.getZ(), s);
                    ie.setVelocity(v.x, 0.2, v.z);
                    ie.setPickupDelay(10);
                    sw.spawnEntity(ie);
                }
            }
            e.bag.clear();
            if (e.jobDone) {
                e.job = Job.NONE;
                e.jobDone = false;
                e.say("terminei, toma aí.");
            } else {
                e.say("toma, vou continuar.");
            }
        }
    }
}
