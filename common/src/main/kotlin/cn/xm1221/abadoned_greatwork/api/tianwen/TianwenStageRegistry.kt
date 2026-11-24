package cn.xm1221.abadoned_greatwork.api.tianwen

import java.util.concurrent.ConcurrentHashMap

/**
 * 问天舞台类型注册中心（客户端）。
 *
 * 内置提供 `hex_grid`、`world_3d`、`interaction_2d`；外部模组可通过 [TianwenApi.registerStageType] 扩展。
 */
object TianwenStageRegistry {

    private val renderers = ConcurrentHashMap<String, () -> TianwenStageRenderer>()

    fun register(id: String, factory: () -> TianwenStageRenderer) {
        renderers[id.lowercase()] = factory
    }

    fun create(id: String): TianwenStageRenderer? =
        renderers[id.lowercase()]?.invoke()

    fun registeredIds(): Set<String> = renderers.keys.toSet()
}
