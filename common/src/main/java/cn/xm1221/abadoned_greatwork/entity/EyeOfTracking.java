package cn.xm1221.abadoned_greatwork.entity;

import cn.xm1221.abadoned_greatwork.entity.eye.AbstractEye;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 追踪之眼 —— 追踪一个实体：每 tick 朝实体当前位置重新瞄准（原版末影之眼飞行），
 * 悬停在目标上方 2~3 格处跟随；目标死亡/消失/换维度则消散，
 * 悬停跟随一段时间后自行碎裂。
 */
public class EyeOfTracking extends EyeOfEnder implements AbstractEye {
    private static final String KEY_TARGET_UUID = "TargetUuid";
    private static final String KEY_OWNER = "Owner";
    /** 悬停跟随多久（tick）后自行碎裂 */
    private static final int HOVER_TICKS = 80;

    private Entity targetEntity;
    private UUID owner;
    private int hoverTicks = 0;

    public EyeOfTracking(EntityType<? extends EyeOfEnder> type, Level level) {
        super(type, level);
    }

    public EyeOfTracking(Level level, double x, double y, double z) {
        this(Abadoned_greatworkEntityTypes.EYE_OF_TRACKING.getValue(), level);
        this.setPos(x, y, z);
    }

    @Override
    public void setTarget(EyeTarget target) {
        if (target instanceof EyeTarget.EntityTarget et) {
            this.targetEntity = et.entity();
        }
    }

    @Override
    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    @Override
    public void launchToward(Vec3 direction) {
        this.signalTo(BlockPos.containing(this.position().add(direction)));
    }

    @Override
    public boolean hasFoundTarget() {
        return this.targetEntity != null
            && this.targetEntity.isAlive()
            && this.distanceToSqr(this.targetEntity) < 400.0; // 20 格内视为追上
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            if (this.targetEntity == null || !this.targetEntity.isAlive()
                || this.targetEntity.level() != this.level()) {
                // 目标死亡/消失/换维度 → 消散
                this.discard();
                return;
            }
            // 每 tick 朝实体当前位置重新瞄准（跟随），并保持在其上方 6 格左右
            this.signalTo(this.targetEntity.blockPosition());
            this.correctAltitude(this.targetEntity.getY() + 6.0);
        }
        // 原版飞行（signalTo 每 tick 重置 life，因此永不碎裂，持续跟随）
        super.tick();
    }

    /** 把高度拉回目标上方附近，并阻尼垂直速度（signalTo 远分支会把目标抬到 y+8） */
    private void correctAltitude(double desiredY) {
        double dy = desiredY - this.getY();
        if (Math.abs(dy) > 0.5) {
            this.setPos(this.getX(), this.getY() + dy * 0.15, this.getZ());
            Vec3 v = this.getDeltaMovement();
            if (Math.abs(v.y) > 0.3) {
                this.setDeltaMovement(v.x, v.y * 0.3, v.z);
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.targetEntity != null) {
            tag.putUUID(KEY_TARGET_UUID, this.targetEntity.getUUID());
        }
        if (this.owner != null) {
            tag.putUUID(KEY_OWNER, this.owner);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(KEY_TARGET_UUID) && this.level() instanceof ServerLevel sl) {
            this.targetEntity = sl.getEntity(tag.getUUID(KEY_TARGET_UUID));
        }
        if (tag.hasUUID(KEY_OWNER)) {
            this.owner = tag.getUUID(KEY_OWNER);
        }
    }
}
