package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * 一个合并点的「到达登记表」。
 *
 * ## 为什么要有这个东西
 * 引擎里一条波**没有等待态**：`CircleExecutionState.tick` 要求 `acceptControlFlow` 当场
 * 返回 `Continue` 或 `Stop`。所以「多路汇一路」只能让先到的波 `Stop()` 掉，把它的
 * [CircleExecutionState]（其实只需要 `currentImage`）留在这里，等凑齐了再由**最后到达的那条**
 * 带着合并结果继续走。
 *
 * ## 凑不齐怎么办
 * 靠促动石的「**栅栏**」兜底：一旦全环一条活波都没有了，就说明再也不会有人来了，
 * 由 `ImpetusBranchMixin` 调 [flushToBranch] 把已经到手的凑合出一份放出去。
 * 因为每条波都必然在有限时间内死掉，这个状态一定会出现，不会死锁。
 *
 * @property pos 合并点自己的坐标（`Stop` 的那一格）
 * @property outDir 唯一的可行出口方向，栅栏放行时要从这一面外面一格造新波
 */
class MergeBuffer(val pos: BlockPos, val outDir: Direction) {

    /**
     * 已经停在这儿等着的波，**按到达顺序**。
     *
     * 存整个状态而不是只存 [CircleExecutionState.currentImage]，是因为新造一条波需要
     * 一个父状态来共享 `knownPositions` / `reachedPositions`（见 [ForkedExecutionState.fork]）。
     */
    val arrivals: MutableList<CircleExecutionState> = ArrayList()

    /**
     * 栅栏兜底：不到 [arrivals] 数量的期望值也放行。
     *
     * 以**第一个到的**为底合并（顺序可复现），然后在 [outDir] 外面一格造一条新波。
     * 出口外面一格而不是本格：本格刚才是 `Stop` 的，从这里出发会立刻再次登记自己，死循环。
     *
     * @return 造出来的波；[arrivals] 是空表时返回 `null`（没什么可放的）
     */
    fun flushToBranch(): ForkedExecutionState? {
        val first = arrivals.firstOrNull() ?: return null
        val merged = CircleImageMerge.merge(arrivals.map { it.currentImage })
        return ForkedExecutionState.fork(
            parent = first,
            currentPos = pos.relative(outDir),
            enteredFrom = outDir,
            image = merged,
        )
    }
}
