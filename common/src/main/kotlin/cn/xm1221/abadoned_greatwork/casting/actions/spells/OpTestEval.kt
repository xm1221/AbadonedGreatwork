package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.getInt
import at.petrak.hexcasting.api.casting.getList
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapBadOffhandItem
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.world.entity.player.Player

class OpTestEval(): SpellAction {
    override val argc: Int
        get() = 5

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): SpellAction.Result {
        val caster = env.castingEntity
        if (caster !is Player) {
            throw MishapBadCaster()
        }
        val hand = env.otherHand
        val stack = caster.getItemInHand(hand)
        if (stack.item !is ItemTruthCrystal) {
            throw MishapBadOffhandItem.of(stack, "truth_crystal")
        }
        if (!caster.isCreative && !ItemTruthCrystal.isCrafted(stack)) {
            throw MishapBadCaster()
        }
        val list0 = args.getList(0,argc)
        val list1 = args.getList(1,argc)
        val list2 = args.getList(2,argc)
        val list3 = args.getList(3,argc)
        val length = args.getInt(4, argc)
        return SpellAction.Result(
            object : RenderedSpell {
                override fun cast(env: CastingEnvironment) {
                    ItemTruthCrystal.setInput(stack,ListIota(list0),ListIota(list1))
                    ItemTruthCrystal.setOutput(stack,ListIota(list2),ListIota(list3))
                    ItemTruthCrystal.setLengthLimit(stack,length)
                }
            },
            0,
            listOf()
        )
    }
}