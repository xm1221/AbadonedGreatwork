package cn.xm1221.abadoned_greatwork.entity;

import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig;
import cn.xm1221.abadoned_greatwork.entity.eye.AbstractEye;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 探古之眼 —— 移动与生命周期完全交给原版 {@link EyeOfEnder#tick()}（super.tick()）：
 * 抛出时 {@link #signalTo} 一次（向目标方向飞 12 格、悬停），80 tick 后原版自动收尾
 * （80% 掉回物品 / 20% 碎裂特效）。
 * <p>
 * 本类只做两件事：首次预检该维度是否可能生成目标（不可能则立即失败）；
 * 每 30 tick 大范围扫描确定方向——找到目标则 {@link #signalTo} 一次转向它，之后停止扫描，
 * 交给原版生命周期收尾。
 */
public class EyeOfLocating extends EyeOfEnder implements AbstractEye {
    private static final String KEY_TARGET_ID = "TargetId";
    private static final String KEY_IS_STRUCTURE = "IsStructure";
    private static final String KEY_SIGNAL_X = "SignalX";
    private static final String KEY_SIGNAL_Y = "SignalY";
    private static final String KEY_SIGNAL_Z = "SignalZ";
    private static final String KEY_OWNER = "Owner";

    /** 扫描间隔（tick） */
    private static final int SCAN_INTERVAL = 30;
    /** 群系扫描半径（格） */
    private static final int BIOME_SCAN_RADIUS = 1600;
    /** 结构扫描半径（区块，1 区块 = 16 格） */
    private static final int STRUCTURE_SCAN_RADIUS = 200;

    private String targetId = "";
    private boolean isStructure = false;
    private boolean found = false;
    private UUID owner = null;
    private BlockPos signalTarget = null;

    private int scanCooldown = 0;
    private boolean checkedPossible = false;
    /** 调试：找到目标后的计时，用于"转向完成"后发送消息 */
    private int foundTicks = 0;
    private boolean debugSent = false;

    public EyeOfLocating(EntityType<? extends EyeOfEnder> type, Level level) {
        super(type, level);
    }

    public EyeOfLocating(Level level, double x, double y, double z) {
        this(Abadoned_greatworkEntityTypes.EYE_OF_LOCATING.getValue(), level);
        this.setPos(x, y, z);
    }

    @Override
    public void setTarget(EyeTarget target) {
        if (target instanceof EyeTarget.BiomeStructure bs) {
            this.setTarget(bs.id(), bs.isStructure());
        }
    }

    /** 便捷方法：直接指定群系或结构 */
    public void setTarget(ResourceLocation id, boolean isStructure) {
        this.targetId = id.toString();
        this.isStructure = isStructure;
        // 写入携带物品，保证 Item NBT / 碎裂返回的物品都带目标
        var stack = this.getItem();
        if (!stack.isEmpty()) {
            stack.getOrCreateTag().putString(KEY_TARGET_ID, this.targetId);
            stack.getOrCreateTag().putBoolean(KEY_IS_STRUCTURE, this.isStructure);
            this.setItem(stack);
        }
    }

    @Override
    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    @Override
    public void launchToward(Vec3 direction) {
        this.signalTarget = BlockPos.containing(this.position().add(direction));
        this.signalTo(this.signalTarget);
    }

    @Override
    public boolean hasFoundTarget() {
        return this.found;
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            // 首次检查：该维度是否可能生成目标，不可能则立即失败
            if (!this.checkedPossible) {
                this.checkedPossible = true;
                boolean impossible = this.isStructure
                    ? this.lookupStructureHolder() == null
                    : !this.canBiomeSpawnInDimension();
                if (impossible) {
                    this.fail();
                    return;
                }
            }

            // 找到前周期性大范围扫描：找到则转向目标一次，之后停止扫描，交给原版收尾
            if (!this.found && --this.scanCooldown <= 0) {
                this.scanCooldown = SCAN_INTERVAL;
                this.scan();
            }

            // 调试：找到目标并转向完成后（约 25 tick），向召唤者发送朝向与目标位置
            if (this.found && !this.debugSent && ++this.foundTicks >= 25) {
                this.debugSent = true;
                this.sendDebugFound();
            }
        }
        // 完全照搬原版：速度驱动、朝向跟随、粒子、life>80 后按原版生命周期结尾（80% 掉物品 / 20% 碎裂）
        super.tick();
    }

    /** 调试开关开启时，向召唤者发送眼睛朝向与找到的目标位置 */
    private void sendDebugFound() {
        if (this.owner == null || !Abadoned_greatworkServerConfig.getConfig().getDebugEyeLocating()) {
            return;
        }
        var player = this.level().getPlayerByUUID(this.owner);
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        var target = Vec3.atCenterOf(this.signalTarget);
        var dir = target.subtract(this.position()).normalize();
        sp.sendSystemMessage(Component.literal(
            "[EyeOfLocating] target: " + this.targetId
                + " | position: (" + (int) target.x + ", " + (int) target.y + ", " + (int) target.z + ")"
                + " | direction: ("
                + String.format("%.2f", dir.x) + ", " + String.format("%.2f", dir.y) + ", " + String.format("%.2f", dir.z)
                + ")"));
    }

    private void scan() {
        BlockPos eyePos = this.blockPosition();
        if (this.isStructure) {
            var holder = this.lookupStructureHolder();
            if (holder == null) {
                this.fail();
                return;
            }
            var level = (ServerLevel) this.level();
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(
                level, HolderSet.direct(List.of(holder)), eyePos, STRUCTURE_SCAN_RADIUS, false);
            if (found != null) {
                this.found = true;
                this.signalTarget = found.getFirst();
                this.signalTo(this.signalTarget);
            }
        } else {
            var key = ResourceKey.create(Registries.BIOME, ResourceLocation.tryParse(this.targetId));
            var level = (ServerLevel) this.level();
            var found = level.findClosestBiome3d(
                h -> h.unwrapKey().map(k -> k.equals(key)).orElse(false),
                eyePos, BIOME_SCAN_RADIUS, 8, 16);
            if (found != null) {
                this.found = true;
                this.signalTarget = found.getFirst();
                this.signalTo(this.signalTarget);
            }
        }
    }

    private net.minecraft.core.Holder.Reference<net.minecraft.world.level.levelgen.structure.Structure> lookupStructureHolder() {
        var id = ResourceLocation.tryParse(this.targetId);
        if (id == null) {
            return null;
        }
        var key = ResourceKey.create(Registries.STRUCTURE, id);
        return this.level().registryAccess()
            .registryOrThrow(Registries.STRUCTURE)
            .getHolder(key).orElse(null);
    }

    private boolean canBiomeSpawnInDimension() {
        var id = ResourceLocation.tryParse(this.targetId);
        if (id == null) {
            return false;
        }
        var key = ResourceKey.create(Registries.BIOME, id);
        var possible = ((ServerLevel) this.level()).getChunkSource().getGenerator()
            .getBiomeSource().possibleBiomes();
        return possible.stream().anyMatch(h ->
            h.unwrapKey().map(k -> k.equals(key)).orElse(false));
    }

    // ==================== 失败（维度不可能生成目标） ====================

    /**
     * 与原版生命周期结尾一致（EyeOfEnder.tick 的 life>80 分支）：
     * 音效 → 消散 → 80% 掉回物品 / 20% 碎裂特效
     */
    private void fail() {
        if (this.owner != null) {
            var player = this.level().getPlayerByUUID(this.owner);
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(
                    Component.translatable("hexcasting.mishap.not_found", this.targetId));
            }
        }
        this.playSound(SoundEvents.ENDER_EYE_DEATH, 1.0F, 1.0F);
        this.discard();
        if (this.random.nextInt(5) > 0) {
            // 80%：掉回物品（带目标 NBT，可再次抛出继续寻找）
            this.level().addFreshEntity(
                new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), this.getItem()));
        } else {
            // 20%：碎裂特效（原版末影之眼碎裂）
            this.level().levelEvent(2003, this.blockPosition(), 0);
        }
    }

    // ==================== NBT ====================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(KEY_TARGET_ID, this.targetId);
        tag.putBoolean(KEY_IS_STRUCTURE, this.isStructure);
        if (this.signalTarget != null) {
            tag.putInt(KEY_SIGNAL_X, this.signalTarget.getX());
            tag.putInt(KEY_SIGNAL_Y, this.signalTarget.getY());
            tag.putInt(KEY_SIGNAL_Z, this.signalTarget.getZ());
        }
        if (this.owner != null) {
            tag.putUUID(KEY_OWNER, this.owner);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.targetId = tag.getString(KEY_TARGET_ID);
        this.isStructure = tag.getBoolean(KEY_IS_STRUCTURE);
        if (tag.contains(KEY_SIGNAL_X)) {
            this.signalTarget = new BlockPos(
                tag.getInt(KEY_SIGNAL_X), tag.getInt(KEY_SIGNAL_Y), tag.getInt(KEY_SIGNAL_Z));
            // 原版不保存 tx/ty/tz，重载后需要重新 signalTo 恢复飞行目标
            this.signalTo(this.signalTarget);
        }
        if (tag.hasUUID(KEY_OWNER)) {
            this.owner = tag.getUUID(KEY_OWNER);
        }
        this.checkedPossible = false;
    }
}
