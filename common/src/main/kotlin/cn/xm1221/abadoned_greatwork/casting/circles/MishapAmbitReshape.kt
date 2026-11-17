package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.GarbageIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.state.BlockState

/**
 * 在法术环组件 `acceptControlFlow` 中触发指定事故的共享辅助函数。
 */
internal fun Mishap.triggerInCircle(image: CastingImage, env: CircleCastEnv, bs: BlockState) {
    val ctx = Mishap.Context(null, bs.block.name)
    val effect = OperatorSideEffect.DoMishap(this, ctx)
    effect.performEffect(CastingVM(image, env))
    env.postExecution(
        CastResult(
            GarbageIota(),
            SpellContinuation.Done,
            image,
            listOf(effect),
            resolutionType(env),
            HexEvalSounds.MISHAP,
        )
    )
}

/**
 * 事故：目标频道绑定的范围锚点不足 3 枚（无法闭合形成平面域）。
 */
class MishapNotEnoughAmbitAnchors(
    val channelDisplay: Component,
    val anchorCount: Int,
) : Mishap() {
    override fun accentColor(ctx: CastingEnvironment, errorCtx: Context): FrozenPigment =
        dyeColor(DyeColor.PURPLE)

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {
    }

    override fun errorMessage(ctx: CastingEnvironment, errorCtx: Context): Component =
        error(
            "abadoned_greatwork.ambit_not_enough_anchors",
            channelDisplay,
            anchorCount,
        )
}

/**
 * 事故：多边形重塑范围的立体体积（S × H）超过法阵原版方盒上限。
 */
class MishapAmbitBudgetExceeded(
    val neededVolume: Long,
    val budgetVolume: Long,
    val areaBlocks: Long,
    val height: Int,
) : Mishap() {
    override fun accentColor(ctx: CastingEnvironment, errorCtx: Context): FrozenPigment =
        dyeColor(DyeColor.RED)

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {
    }

    override fun errorMessage(ctx: CastingEnvironment, errorCtx: Context): Component =
        error(
            "abadoned_greatwork.ambit_budget_exceeded",
            neededVolume,
            areaBlocks,
            height,
            budgetVolume,
        )
}

/**
 * 事故：频道内存在尚未手动指定顺位（`order == 0`）的道引标。
 */
class MishapUnassignedAmbitOrder(
    val channelDisplay: Component,
    val anchorPos: BlockPos,
) : Mishap() {
    override fun accentColor(ctx: CastingEnvironment, errorCtx: Context): FrozenPigment =
        dyeColor(DyeColor.YELLOW)

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {
    }

    override fun errorMessage(ctx: CastingEnvironment, errorCtx: Context): Component =
        error(
            "abadoned_greatwork.ambit_unassigned_order",
            channelDisplay,
            anchorPos.toShortString(),
        )
}

/**
 * 事故：同频道内有两根以上道引标占用了相同的顺位号。
 */
class MishapDuplicateAmbitOrder(
    val channelDisplay: Component,
    val order: Long,
    val posA: BlockPos,
    val posB: BlockPos,
) : Mishap() {
    override fun accentColor(ctx: CastingEnvironment, errorCtx: Context): FrozenPigment =
        dyeColor(DyeColor.ORANGE)

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {
    }

    override fun errorMessage(ctx: CastingEnvironment, errorCtx: Context): Component =
        error(
            "abadoned_greatwork.ambit_duplicate_order",
            channelDisplay,
            order,
            posA.toShortString(),
            posB.toShortString(),
        )
}
