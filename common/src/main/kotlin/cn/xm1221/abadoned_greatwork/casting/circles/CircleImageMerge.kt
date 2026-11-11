package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage

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
        for (i in 1 until branches.size) {
            merged = merged.copy(stack = merged.stack + branches[i].stack)
        }

        return merged.copy(userData = branches.last().userData.copy())
    }
}
