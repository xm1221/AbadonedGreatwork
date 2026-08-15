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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 探古之眼 —— 搜索在使用点（物品 use / 法术 execute）同步完成，本实体不搜索。
 * <p>
 * 移动与生命周期完全交给原版 {@link EyeOfEnder#tick()}（super.tick()）：
 * 召唤时 {@link #aimAt} 一次（向目标方向飞 12 格、悬停），80 tick 后原版自动收尾
 * （80% 掉回物品 / 20% 碎裂特效）。
 */
public class EyeOfLocating extends EyeOfEnder implements AbstractEye {
    private static final String KEY_TARGET_ID = "TargetId";
    private static final String KEY_IS_STRUCTURE = "IsStructure";
    private static final String KEY_SIGNAL_X = "SignalX";
    private static final String KEY_SIGNAL_Y = "SignalY";
    private static final String KEY_SIGNAL_Z = "SignalZ";
    private static final String KEY_OWNER = "Owner";

    /** 群系搜索半径（格） */
    private static final int BIOME_SCAN_RADIUS = 800;
    /** 结构搜索半径（区块，1 区块 = 16 格） */
    private static final int STRUCTURE_SCAN_RADIUS = 100;

    private String targetId = "";
    private boolean isStructure = false;
    private boolean found = false;
    private UUID owner = null;
    private BlockPos signalTarget = null;

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

    /** 便捷方法：直接指定群系或结构（目标已在使用点确定，不再搜索） */
    public void setTarget(ResourceLocation id, boolean isStructure) {
        this.targetId = id.toString();
        this.isStructure = isStructure;
        this.found = true;
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

    /** 瞄准使用点搜索到的目标（完全原版：signalTo 一次，向目标方向飞 12 格后悬停） */
    public void aimAt(BlockPos pos) {
        this.signalTarget = pos;
        this.signalTo(pos);
    }

    @Override
    public boolean hasFoundTarget() {
        return this.found;
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            // 调试：转向完成后（约 25 tick），向召唤者发送朝向与目标位置
            if (this.found && !this.debugSent && ++this.foundTicks >= 25) {
                this.debugSent = true;
                this.sendDebugFound();
            }
        }
        // 完全照搬原版：速度驱动、朝向跟随、粒子、life>80 后按原版生命周期结尾（80% 掉物品 / 20% 碎裂）
        super.tick();
    }

    /**
     * 使用点同步搜索：找到最近的目标返回其位置，找不到（或目标不可能存在）返回 null。
     * 由物品 {@code use()} 与法术 {@code execute()} 在服务器主线程调用，与原版末影之眼一致。
     */
    public static BlockPos searchTarget(ServerLevel level, BlockPos from, ResourceLocation id, boolean isStructure) {
        if (isStructure) {
            var holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
            if (holder == null) {
                return null;
            }
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(
                level, HolderSet.direct(List.of(holder)), from, STRUCTURE_SCAN_RADIUS, false);
            return found != null ? found.getFirst() : null;
        } else {
            var key = ResourceKey.create(Registries.BIOME, id);
            var found = level.findClosestBiome3d(
                h -> h.unwrapKey().map(k -> k.equals(key)).orElse(false),
                from, BIOME_SCAN_RADIUS, 8, 16);
            return found != null ? found.getFirst() : null;
        }
    }

    /** 调试开关开启时，向召唤者发送眼睛朝向与目标位置 */
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
        this.found = !this.targetId.isEmpty();
        if (tag.contains(KEY_SIGNAL_X)) {
            this.signalTarget = new BlockPos(
                tag.getInt(KEY_SIGNAL_X), tag.getInt(KEY_SIGNAL_Y), tag.getInt(KEY_SIGNAL_Z));
            // 原版不保存 tx/ty/tz，重载后需要重新 signalTo 恢复飞行目标
            this.signalTo(this.signalTarget);
        }
        if (tag.hasUUID(KEY_OWNER)) {
            this.owner = tag.getUUID(KEY_OWNER);
        }
    }
}
