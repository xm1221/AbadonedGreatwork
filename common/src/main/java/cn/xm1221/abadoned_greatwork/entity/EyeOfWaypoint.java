package cn.xm1221.abadoned_greatwork.entity;

import cn.xm1221.abadoned_greatwork.entity.eye.AbstractEye;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 路标之眼 —— 追踪一个具体坐标：每 tick 朝目标坐标重新瞄准（原版末影之眼飞行），
 * 到达 5 格内后悬停一段时间，再按原版结尾碎裂。
 */
public class EyeOfWaypoint extends EyeOfEnder implements AbstractEye {
    private static final String KEY_TARGET_X = "TargetX";
    private static final String KEY_TARGET_Y = "TargetY";
    private static final String KEY_TARGET_Z = "TargetZ";
    private static final String KEY_OWNER = "Owner";
    private static final int HOVER_TICKS = 40;

    private BlockPos waypoint;
    private UUID owner;
    private int hoverTicks = 0;

    public EyeOfWaypoint(EntityType<? extends EyeOfEnder> type, Level level) {
        super(type, level);
    }

    public EyeOfWaypoint(Level level, double x, double y, double z) {
        this(Abadoned_greatworkEntityTypes.EYE_OF_WAYPOINT.getValue(), level);
        this.setPos(x, y, z);
    }

    @Override
    public void setTarget(EyeTarget target) {
        if (target instanceof EyeTarget.PositionTarget pt) {
            this.waypoint = pt.pos();
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
        return this.waypoint != null
            && this.distanceToSqr(Vec3.atCenterOf(this.waypoint)) < 25.0;
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            if (this.waypoint == null) {
                this.discard();
                return;
            }
            double distSq = this.distanceToSqr(Vec3.atCenterOf(this.waypoint));
            if (distSq < 25.0) { // 5 格内：悬停后按原版结尾碎裂
                if (++this.hoverTicks >= HOVER_TICKS) {
                    this.breakEye();
                    return;
                }
            } else {
                this.hoverTicks = 0;
            }
            // 每 tick 朝坐标重新瞄准（持续逼近），并保持在其上方 2 格左右
            this.signalTo(this.waypoint);
            this.correctAltitude(this.waypoint.getY() + 2.0);
        }
        // 原版飞行
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

    /** 碎裂结尾：音效 + 消散 + 末影之眼碎裂特效（不带物品，路标眼没有可返回的物品） */
    private void breakEye() {
        this.playSound(SoundEvents.ENDER_EYE_DEATH, 1.0F, 1.0F);
        this.discard();
        this.level().levelEvent(2003, this.blockPosition(), 0);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.waypoint != null) {
            tag.putInt(KEY_TARGET_X, this.waypoint.getX());
            tag.putInt(KEY_TARGET_Y, this.waypoint.getY());
            tag.putInt(KEY_TARGET_Z, this.waypoint.getZ());
        }
        if (this.owner != null) {
            tag.putUUID(KEY_OWNER, this.owner);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_TARGET_X)) {
            this.waypoint = new BlockPos(
                tag.getInt(KEY_TARGET_X), tag.getInt(KEY_TARGET_Y), tag.getInt(KEY_TARGET_Z));
            this.signalTo(this.waypoint);
        }
        if (tag.hasUUID(KEY_OWNER)) {
            this.owner = tag.getUUID(KEY_OWNER);
        }
    }
}
