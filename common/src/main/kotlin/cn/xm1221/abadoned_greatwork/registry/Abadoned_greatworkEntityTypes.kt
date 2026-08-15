package cn.xm1221.abadoned_greatwork.registry

import cn.xm1221.abadoned_greatwork.entity.EyeOfLocating
import cn.xm1221.abadoned_greatwork.entity.EyeOfTracking
import cn.xm1221.abadoned_greatwork.entity.EyeOfWaypoint
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

/**
 * 实体类型注册器。
 */
object Abadoned_greatworkEntityTypes : Abadoned_greatworkRegistrar<EntityType<*>>(
    Registries.ENTITY_TYPE,
    { BuiltInRegistries.ENTITY_TYPE },
) {
    /** 探古之眼（定位群系/结构） */
    @JvmField
    val EYE_OF_LOCATING = register("eye_of_locating") {
        EntityType.Builder.of(::EyeOfLocating, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(4)
            .updateInterval(4)
            .build("eye_of_locating")
    }

    /** 追踪之眼（追踪实体） */
    @JvmField
    val EYE_OF_TRACKING = register("eye_of_tracking") {
        EntityType.Builder.of(::EyeOfTracking, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(4)
            .updateInterval(4)
            .build("eye_of_tracking")
    }

    /** 路标之眼（追踪具体坐标） */
    @JvmField
    val EYE_OF_WAYPOINT = register("eye_of_waypoint") {
        EntityType.Builder.of(::EyeOfWaypoint, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(4)
            .updateInterval(4)
            .build("eye_of_waypoint")
    }
}
