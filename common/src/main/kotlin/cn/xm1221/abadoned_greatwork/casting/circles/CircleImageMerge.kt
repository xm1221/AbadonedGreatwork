package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import net.minecraft.core.BlockPos
import net.minecraft.nbt.Tag

/**
 * 合并（多路汇一路）用的镜像处理函数。
 *
 * 调用点有两个，都在多路汇一路的路径上：
 * - [MultiDirectrixBlock][cn.xm1221.abadoned_greatwork.block.MultiDirectrixBlock] 的
 *   `acceptControlFlow`：最后一条到达的波凑够数时，合并后由它继续往下走；
 * - [MergeBuffer.flushToBranch]：促动石**栅栏**兜底（永远凑不齐了）时，用已到手的凑合放行。
 *
 * ## 语义（咩咩拍板）
 * - **栈**：先到的把自己的元素入栈，后到的同样把自己的元素全部入栈，所以按**到达顺序**
 *   从下往上摞（`ListIota` 的约定是「表尾 = 栈顶」）。
 * - **渡鸦之思**：先到的填入自己的，**后到的覆盖**先到的 —— 最终取**最后一个到达**的那份。
 *   Hex 把它存在 `image.userData` 的 `hexcasting:ravenmind` 键下
 *   （`HexAPI.RAVENMIND_USERDATA`，读写见 `OpPushLocal` / `OpPeekLocal`），所以拿 `userData` 就够。
 *   必须 `copy()`：`userData` 会被 VM 就地改写，不能和已经死掉的那条波共用同一份。
 * - 运算符计数不累加（`tick` 拿到 `Continue` 之后自己会 `withOverriddenUsedOps(0)`）。
 */
object CircleImageMerge {

    const val TAG_MERGED_IMPETUSES = "abw_merged_impetuses"

    /**
     * 把多份分支镜像合成一份。
     *
     * @param branches 按**到达顺序**排列，至少一份；只有一份时原样返回（不复制 —— 除了
     *                 `userData` 之外 [CastingImage] 都不可变，而这里也不会再去动它）
     */
    fun merge(branches: List<CastingImage>): CastingImage {
        require(branches.isNotEmpty()) { "CircleImageMerge.merge 至少要一份镜像" }
        if (branches.size == 1) return branches[0]

        var merged = branches[0]
        val impetusSet = LinkedHashSet<Long>()
        readImpetusLongs(branches[0]).forEach(impetusSet::add)
        for (i in 1 until branches.size) {
            merged = merged.copy(stack = merged.stack + branches[i].stack)
            readImpetusLongs(branches[i]).forEach(impetusSet::add)
        }

        val ud = branches.last().userData.copy()
        if (impetusSet.isNotEmpty()) {
            ud.putLongArray(TAG_MERGED_IMPETUSES, impetusSet.toLongArray())
        }
        return merged.copy(userData = ud)
    }

    /**
     * 合并多条到达分支的执行态镜像，并把各自携带的促动石坐标一并合并写入镜像元数据。
     */
    fun mergeStates(arrivals: List<CircleExecutionState>): CastingImage {
        require(arrivals.isNotEmpty()) { "CircleImageMerge.mergeStates 至少要一份执行态" }
        val merged = merge(arrivals.map { it.currentImage })
        return withMergedImpetuses(merged, arrivals)
    }

    /**
     * 把一组到达分支的促动石坐标（以及它们之前已经合并过的促动石坐标）并入 [merged] 的 `userData`。
     */
    fun withMergedImpetuses(merged: CastingImage, arrivals: List<CircleExecutionState>): CastingImage {
        val positions = LinkedHashSet<Long>()
        for (state in arrivals) {
            positions.add(state.impetusPos.asLong())
            readImpetusLongs(state.currentImage).forEach(positions::add)
        }
        val tag = merged.userData.copy()
        tag.putLongArray(TAG_MERGED_IMPETUSES, positions.toLongArray())
        return merged.copy(userData = tag)
    }

    /**
     * 从镜像中提取本次波关联的所有去重促动石坐标（始终包含当前的 [fallback]）。
     */
    fun extractImpetusPositions(image: CastingImage, fallback: BlockPos): List<BlockPos> {
        val result = LinkedHashSet<Long>()
        result.add(fallback.asLong())
        readImpetusLongs(image).forEach(result::add)
        return result.map(BlockPos::of)
    }

    private fun readImpetusLongs(image: CastingImage): LongArray {
        val ud = image.userData
        return if (ud.contains(TAG_MERGED_IMPETUSES, Tag.TAG_LONG_ARRAY.toInt())) {
            ud.getLongArray(TAG_MERGED_IMPETUSES)
        } else {
            LongArray(0)
        }
    }
}
