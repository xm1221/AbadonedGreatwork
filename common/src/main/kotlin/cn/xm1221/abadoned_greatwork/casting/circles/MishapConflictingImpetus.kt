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
 * 事故：来自不同促动石的媒质流在同一个分合石处相遇。
 */
class MishapConflictingImpetus(
    val directrixPos: BlockPos,
    val otherImpetusPos: BlockPos,
) : Mishap() {

    override fun accentColor(ctx: CastingEnvironment, errorCtx: Context): FrozenPigment =
        dyeColor(DyeColor.MAGENTA)

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: at.petrak.hexcasting.api.utils.TreeList<Iota>): at.petrak.hexcasting.api.utils.TreeList<Iota> {
        return stack
    }

    override fun errorMessage(ctx: CastingEnvironment, errorCtx: Context): Component =
        error(
            "abadoned_greatwork.conflicting_impetus",
            otherImpetusPos.toShortString(),
            directrixPos.toShortString(),
        )

    /** 在法术环执行上下文中触发本事故并广播音效与提示。 */
    fun triggerInCircle(image: CastingImage, env: CircleCastEnv, bs: BlockState) {
        val ctx = Context(null, bs.block.name)
        val effect = OperatorSideEffect.DoMishap(this, ctx)
        effect.performEffect(CastingVM(image, env))
        env.postExecution(
            CastResult(
                GarbageIota(),
                SpellContinuation.Done,
                image,
                listOf(effect),
                resolutionType(env),
                HexEvalSounds.MISHAP.get(),
            )
        )
    }
}
