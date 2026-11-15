package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.WeakHashMap

/**
 * 追踪当前正在激活占据各个分合石（[MultiDirectrixBlock] / [MultiDirectrixPBlock]）的促动石坐标，
 * 用于在来自不同促动石的媒质流同时相遇于同一分合石时立即判定冲突并触发事故。
 */
object DirectrixConflictTracker {

    private class LevelState {
        val nodeToImpetus = HashMap<BlockPos, BlockPos>()
        val impetusToNodes = HashMap<BlockPos, MutableSet<BlockPos>>()
    }

    private val levels = WeakHashMap<ServerLevel, LevelState>()

    /**
     * 尝试由 [impetusPos] 登记占据 [nodePos]；若当前已有另一颗仍在运行的促动石占据此分合石，
     * 返回冲突促动石的坐标，否则返回 `null`。
     */
    @JvmStatic
    @Synchronized
    fun claimOrGetConflict(level: ServerLevel, nodePos: BlockPos, impetusPos: BlockPos): BlockPos? {
        val state = levels.computeIfAbsent(level) { LevelState() }
        val nodeKey = nodePos.immutable()
        val impetusKey = impetusPos.immutable()

        val existing = state.nodeToImpetus[nodeKey]
        if (existing != null) {
            if (existing == impetusKey) {
                return null
            }
            if (isImpetusStillActive(level, existing)) {
                return existing
            }
            // 原促动石已卸载/损坏/停止却未及时释放时自愈清理
            removeNodeEntry(state, nodeKey, existing)
        }

        state.nodeToImpetus[nodeKey] = impetusKey
        state.impetusToNodes.computeIfAbsent(impetusKey) { HashSet() }.add(nodeKey)
        return null
    }

    /** 释放某颗促动石本次施法过程中登记的所有分合石占据记录。 */
    @JvmStatic
    @Synchronized
    fun releaseImpetus(level: ServerLevel, impetusPos: BlockPos) {
        val state = levels[level] ?: return
        val nodes = state.impetusToNodes.remove(impetusPos) ?: return
        for (node in nodes) {
            if (state.nodeToImpetus[node] == impetusPos) {
                state.nodeToImpetus.remove(node)
            }
        }
        if (state.nodeToImpetus.isEmpty() && state.impetusToNodes.isEmpty()) {
            levels.remove(level)
        }
    }

    /** 方块被破坏或替换时移除该坐标的占据记录。 */
    @JvmStatic
    @Synchronized
    fun releaseNode(level: ServerLevel, nodePos: BlockPos) {
        val state = levels[level] ?: return
        val owner = state.nodeToImpetus.remove(nodePos) ?: return
        state.impetusToNodes[owner]?.let { owned ->
            owned.remove(nodePos)
            if (owned.isEmpty()) {
                state.impetusToNodes.remove(owner)
            }
        }
        if (state.nodeToImpetus.isEmpty() && state.impetusToNodes.isEmpty()) {
            levels.remove(level)
        }
    }

    private fun removeNodeEntry(state: LevelState, nodeKey: BlockPos, owner: BlockPos) {
        state.nodeToImpetus.remove(nodeKey)
        state.impetusToNodes[owner]?.let { owned ->
            owned.remove(nodeKey)
            if (owned.isEmpty()) {
                state.impetusToNodes.remove(owner)
            }
        }
    }

    private fun isImpetusStillActive(level: ServerLevel, impetusPos: BlockPos): Boolean {
        if (!level.isLoaded(impetusPos)) return false
        val be = level.getBlockEntity(impetusPos) as? BlockEntityAbstractImpetus ?: return false
        if (be.executionState != null) return true
        val host = be as? ImpetusBranchHost ?: return false
        return host.abwBranches().isNotEmpty() || host.abwMergeBuffers().isNotEmpty()
    }
}
