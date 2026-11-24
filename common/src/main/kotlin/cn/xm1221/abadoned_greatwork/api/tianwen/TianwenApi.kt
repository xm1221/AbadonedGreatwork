package cn.xm1221.abadoned_greatwork.api.tianwen

import cn.xm1221.abadoned_greatwork.client.ponder.PonderClientState
import cn.xm1221.abadoned_greatwork.ponder.data.PonderSceneLoader
import cn.xm1221.abadoned_greatwork.ponder.data.PonderUnlockManager
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/**
 * 「问天（Tianwen）」对外开放 API 入口。
 *
 * 其他模组（软依赖 `abadoned_greatwork`）可在客户端或服务端通过此类：
 * 1. 注册自定义演示舞台（[registerStageType]），配合数据驱动 JSON 中的 `default_stage` / `step.stage` 使用；
 * 2. 注册自定义数据驱动解锁条件评估器（[registerUnlockEvaluator]）；
 * 3. 在代码或交互中直接打开目录（[openIndex]）或跳转到指定演示章节（[openScene]）；
 * 4. 在服务端手动解锁/锁定或重置玩家章节进度。
 */
object TianwenApi {

    const val STAGE_HEX_GRID = "hex_grid"
    const val STAGE_WORLD_3D = "world_3d"
    const val STAGE_INTERACTION_2D = "interaction_2d"

    /**
     * 【客户端】注册自定义舞台渲染器工厂（每个打开的演示界面会按需创建实例）。
     */
    @JvmStatic
    fun registerStageType(stageId: String, factory: () -> TianwenStageRenderer) {
        TianwenStageRegistry.register(stageId, factory)
    }

    /**
     * 【通用/服务端】注册自定义数据驱动解锁条件类型。
     * 当章节 JSON `unlock.conditions` 出现 `{ "type": "<type>", "id": "<target>" }` 时触发。
     */
    @JvmStatic
    fun registerUnlockEvaluator(
        type: String,
        evaluator: (player: ServerPlayer, targetId: String) -> Boolean,
    ) {
        PonderUnlockManager.registerCustomEvaluator(type, evaluator)
    }

    /** 【客户端】打开「问天」章节索引界面。 */
    @JvmStatic
    @JvmOverloads
    fun openIndex(parentScreen: Screen? = null) {
        PonderClientState.openIndex(parentScreen)
    }

    /** 【客户端】请求服务端校验并打开指定演示章节。 */
    @JvmStatic
    @JvmOverloads
    fun openScene(sceneId: ResourceLocation, returnScreen: Screen? = null) {
        PonderClientState.openScene(sceneId, returnScreen)
    }

    /** 【服务端】检查某玩家是否已解锁指定章节。 */
    @JvmStatic
    fun isSceneUnlocked(player: ServerPlayer, sceneId: ResourceLocation): Boolean {
        val scene = PonderSceneLoader.getScene(sceneId) ?: return false
        return PonderUnlockManager.isUnlocked(player, scene)
    }

    /** 【服务端】立即为玩家解锁指定章节并同步目录。 */
    @JvmStatic
    fun unlockScene(player: ServerPlayer, sceneId: ResourceLocation) {
        PonderUnlockManager.unlockScene(player, sceneId)
    }

    /** 【服务端】同步最新章节索引与解锁状态到指定玩家。 */
    @JvmStatic
    fun syncIndex(player: ServerPlayer) {
        PonderSceneLoader.syncIndexTo(player)
    }
}
