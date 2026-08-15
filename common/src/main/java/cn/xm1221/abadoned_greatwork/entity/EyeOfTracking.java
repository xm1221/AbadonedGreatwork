package cn.xm1221.abadoned_greatwork.entity;

import cn.xm1221.abadoned_greatwork.entity.eye.AbstractEye;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 追踪之眼 —— 追踪一个实体：每 tick 朝实体当前位置重新瞄准（原版末影之眼飞行），
 * 悬停在目标上方 2~3 格处跟随；目标死亡/消失/换维度则消散，
 * 悬停跟随一段时间后自行碎裂（50% 掉回带目标 NBT 的物品，可再次抛出）。
 */
public class EyeOfTracking extends EyeOfEnder implements AbstractEye {
    public static final String KEY_TARGET_UUID = "TargetUuid";
    public static final String KEY_TARGET_NAME = "TargetName";
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
            // 写入携带物品（掉回物品时保留目标，可再次抛出）
            var stack = this.getItem();
            if (!stack.isEmpty()) {
                stack.getOrCreateTag().putUUID(KEY_TARGET_UUID, this.targetEntity.getUUID());
                stack.getOrCreateTag().putString(KEY_TARGET_NAME, this.targetEntity.getDisplayName().getString());
                this.setItem(stack);
            }
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
            // 悬停跟随一段时间后自行碎裂（目标跑远则重新计时）
            double distSq = this.distanceToSqr(this.targetEntity);
            if (distSq < 100.0) { // 10 格内视为悬停跟随
                if (++this.hoverTicks >= HOVER_TICKS) {
                    this.breakEye();
                    return;
                }
            } else {
                this.hoverTicks = 0;
            }
            // 每 tick 朝实体当前位置重新瞄准（跟随），并保持在其上方 2~3 格
            this.signalTo(this.targetEntity.blockPosition());
            this.correctAltitude(this.targetEntity.getY() + 2.0);
        }
        // 原版飞行（signalTo 每 tick 重置 life，因此由上面的悬停计时决定何时碎裂）
        super.tick();
    }

    /** 碎裂结尾：音效 + 消散 + 50% 掉回物品（带目标 NBT）/ 50% 碎裂特效 */
    private void breakEye() {
        this.playSound(SoundEvents.ENDER_EYE_DEATH, 1.0F, 1.0F);
        this.discard();
        if (this.random.nextInt(2) == 0) {
            // 50%：掉回物品（带目标 NBT，可再次抛出）
            this.level().addFreshEntity(
                new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), this.getItem()));
        } else {
            // 50%：碎裂特效（原版末影之眼碎裂）
            this.level().levelEvent(2003, this.blockPosition(), 0);
        }
    }

    /** 高度校正：仅通过垂直速度平滑下降（不直接改位置，避免轨迹被反复下压） */
    private void correctAltitude(double desiredY) {
        if (this.getY() > desiredY + 1.0) {
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x, Math.max(v.y - 0.08, -0.25), v.z);
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
