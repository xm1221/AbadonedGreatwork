package cn.xm1221.abadoned_greatwork.entity;

import cn.xm1221.abadoned_greatwork.entity.eye.AbstractEye;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 路标之眼 —— 追踪一个具体坐标：每 tick 朝目标坐标重新瞄准（原版末影之眼飞行），
 * 到达 5 格内后悬停一段时间，再自行碎裂（50% 掉回带目标 NBT 的物品，可再次抛出）。
 */
public class EyeOfWaypoint extends EyeOfEnder implements AbstractEye {
    public static final String KEY_TARGET_X = "TargetX";
    public static final String KEY_TARGET_Y = "TargetY";
    public static final String KEY_TARGET_Z = "TargetZ";
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
            // 写入携带物品（掉回物品时保留目标，可再次抛出）
            var stack = this.getItem();
            if (!stack.isEmpty()) {
                stack.getOrCreateTag().putInt(KEY_TARGET_X, this.waypoint.getX());
                stack.getOrCreateTag().putInt(KEY_TARGET_Y, this.waypoint.getY());
                stack.getOrCreateTag().putInt(KEY_TARGET_Z, this.waypoint.getZ());
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

    /** 高度校正：仅通过垂直速度平滑下降（不直接改位置，避免轨迹被反复下压） */
    private void correctAltitude(double desiredY) {
        if (this.getY() > desiredY + 1.0) {
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x, Math.max(v.y - 0.08, -0.25), v.z);
        }
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
