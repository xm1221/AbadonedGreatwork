package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.pigment.FrozenPigment
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID

/**
 * 多向导引石**真并行**分流出来的「分支波」。
 *
 * ## 为什么需要子类
 * `CircleExecutionState` 的构造器是 `protected` —— 上游留这个可见性就是给人子类化的，
 * 但同一个包里之外的地方只能靠继承拿到构造器。这个类**唯一的作用**就是把那个构造器
 * 暴露成一个 [fork] 工厂，本身不加任何字段、不改任何行为。
 *
 * ## 和母波共享什么
 * | 字段 | 共享? | 原因 |
 * |---|---|---|
 * | `knownPositions` | 共享（同一个 `Set`） | 环已经定死了，分支走不出环；`bounds` 也因此一致 |
 * | `reachedPositions` | **共享（同一个 `List`）** | 主波结束时 `endExecution` 只遍历这一张表，共享才能把分支点亮的格子一起熄掉 |
 * | `currentImage` | **各自一份**（见 [forkImage]） | 分流 = 镜像分家，各走各的栈 |
 * | `currentPos` / `enteredFrom` | 各自 | 每条波在环上的位置不同 |
 *
 * ## 生命周期
 * 由 [ImpetusBranchHost] 持有的列表在促动石上一 tick 一推进（见 `ImpetusBranchMixin`）：
 * - `tick()` 返回 `false`（走到促动石 / 出错停机）→ 这条分支出表。
 * - 母波结束时分支全部出表（它们点亮的格子已随 `reachedPositions` 一起熄掉）。
 */
class ForkedExecutionState(
    impetusPos: BlockPos,
    impetusDir: Direction,
    knownPositions: Set<BlockPos>,
    reachedPositions: List<BlockPos>,
    currentPos: BlockPos,
    enteredFrom: Direction,
    currentImage: CastingImage,
    caster: UUID?,
    casterPigment: FrozenPigment?,
) : CircleExecutionState(
    impetusPos,
    impetusDir,
    knownPositions,
    reachedPositions,
    currentPos,
    enteredFrom,
    currentImage,
    caster,
    casterPigment,
) {
    companion object {
        /**
         * 从母波 [parent] 分出一条落在 [currentPos]、由 [enteredFrom] 那一面驶入的分支。
         *
         * [currentPos] / [enteredFrom] 的取法和引擎推主波时完全一致
         * （`CircleExecutionState.tick` 里 `currentPos = found.getFirst()`）。
         */
        fun fork(
            parent: CircleExecutionState,
            currentPos: BlockPos,
            enteredFrom: Direction,
            image: CastingImage,
        ): ForkedExecutionState = ForkedExecutionState(
            impetusPos = parent.impetusPos,
            impetusDir = parent.impetusDir,
            knownPositions = parent.knownPositions,
            reachedPositions = parent.reachedPositions,
            currentPos = currentPos,
            enteredFrom = enteredFrom,
            currentImage = image,
            caster = parent.caster,
            casterPigment = parent.casterPigment,
        )
    }
}

/**
 * 给分流出来的一支拿到**独立**的镜像。
 *
 * `CastingImage` 本身是不可变的（每个 `withXxx` 都返回新对象），只有 `userData` 这个
 * `CompoundTag` 是会被就地改写的（比如 `checkAndMarkGivenMotion` 往里写标记）。
 * 所以这里只需要深拷贝 `userData`，栈和括号状态可以安全共享。
 *
 * `withOverriddenUsedOps(0)` 对齐引擎推主波时的处理
 * （`currentImage = cont.update.withOverriddenUsedOps(0)`）：一块板岩执行完，算子计数归零。
 */
internal fun CastingImage.forkImage(): CastingImage =
    copy(userData = userData.copy()).withOverriddenUsedOps(0)
