package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage

/**
 * 合并（多路汇一路）用的镜像处理函数。**已接上，但语义还是占位**。
 *
 * 调用点有两个，都在多路汇一路的路径上：
 * - [MultiDirectrixBlock][cn.xm1221.abadoned_greatwork.block.MultiDirectrixBlock] 的
 *   `acceptControlFlow`：最后一条到达的波凑够数时，合并后由它继续往下走；
 * - [MergeBuffer.flushToBranch]：促动石**栅栏**兜底（永远凑不齐了）时，用已到手的凑合放行。
 *
 * ## 当前语义（随时可以推翻）
 * - 以**第一条**镜像为底（它的 `parenCount` / `parenthesized` / `escapeNext` / `userData` 保留）
 * - 其余镜像的**栈按顺序接在底下**（后到的压在下面，所以最后一条在最底）
 * - 算子计数取第一条的，不累加
 *
 * 这套规则纯粹是为了「有个确定的东西」而写，**不代表任何设计意图**：栈长度一变，
 * 上游后续图案取到的 iota 就全错位了，所以真要用起来多半得换成下面第 3 种。
 *
 * ## 备选方向（拍板时挑一个）
 * 1. **只留一条**：汇合 = 取最后到达的那份，其余丢弃（最省事，但分支就白跑了）
 * 2. **栈合并**：就是当前这个占位实现
 * 3. **列表打包**：把每份镜像的栈各打成一个 `ListIota`，压成一个 `ListIota` 列表交给咒术
 *    自己 `Decomposition` —— 最灵活，也最不怕栈错位，但玩家得自己拆
 */
object CircleImageMerge {

    /**
     * 把多份分支镜像合成一份。
     *
     * @param branches 至少一份；只有一份时原样返回（**不复制**，因为 [CastingImage] 不可变）
     */
    fun merge(branches: List<CastingImage>): CastingImage {
        require(branches.isNotEmpty()) { "CircleImageMerge.merge 至少要一份镜像" }

        var merged = branches[0]
        for (i in 1 until branches.size) {
            merged = merged.copy(stack = merged.stack + branches[i].stack)
        }
        return merged
    }
}
