package cn.xm1221.abadoned_greatwork.entity.eye;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * 眼睛的目标 —— 不同眼睛追踪不同类型的目标。
 */
public interface EyeTarget {
    /** 群系或结构（用于探古寻迹） */
    record BiomeStructure(ResourceLocation id, boolean isStructure) implements EyeTarget {
    }

    /** 实体（用于追踪实体） */
    record EntityTarget(Entity entity) implements EyeTarget {
    }

    /** 具体坐标（用于追踪特定坐标） */
    record PositionTarget(BlockPos pos) implements EyeTarget {
    }
}
