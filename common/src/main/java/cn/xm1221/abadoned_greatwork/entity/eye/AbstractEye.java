package cn.xm1221.abadoned_greatwork.entity.eye;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 眼睛（Eye）的抽象接口 —— 用于实现不同类型的探察之眼。
 * <p>
 * 一个"眼睛"需要：目标（见 {@link EyeTarget}）、召唤者、抛出方向，以及是否已找到目标。
 * 目标类型因眼睛而异（群系/结构、实体、坐标），具体实现负责自己的扫描逻辑与生命周期
 * （如 {@code EyeOfLocating} 复用原版末影之眼）。
 */
public interface AbstractEye {
    /** 设定目标（目标类型因眼睛而异） */
    void setTarget(EyeTarget target);

    /** 设定召唤者（用于调试/失败提示） */
    void setOwner(UUID owner);

    /** 朝某个方向"抛出" */
    void launchToward(Vec3 direction);

    /** 是否已找到/追上目标 */
    boolean hasFoundTarget();
}
