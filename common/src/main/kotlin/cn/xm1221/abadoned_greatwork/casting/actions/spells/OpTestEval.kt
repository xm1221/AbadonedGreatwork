package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.getInt
import at.petrak.hexcasting.api.casting.getList
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapBadOffhandItem
import at.petrak.hexcasting.api.utils.putInt
import at.petrak.hexcasting.api.utils.putList
import at.petrak.hexcasting.api.utils.putTag
import at.petrak.hexcasting.api.utils.serializeToNBT
import cn.xm1221.abadoned_greatwork.casting.TestCastingEnv
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.nbt.CompoundTag

class OpTestEval(): SpellAction {
    override val argc: Int
        get() = 5

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): SpellAction.Result {
        val caster  = env.castingEntity
        if(caster == null){
            throw MishapBadCaster()
        }
        val hand = env.otherHand
        val stack = caster.getItemInHand(hand)
        if(stack.item !is ItemTruthCrystal){
            throw MishapBadOffhandItem.of(stack,"truth_crystal")
        }
        val list0 = args.getList(0,argc)
        val list1 = args.getList(1,argc)
        val list2 = args.getList(2,argc)
        val list3 = args.getList(3,argc)
        val length = args.getInt(4, argc)
        return SpellAction.Result(
            object : RenderedSpell {
                override fun cast(env: CastingEnvironment) {
                    stack.putInt(ItemTruthCrystal.LENGTH_LIMIT,length)
                    val inputs = CompoundTag()
                    inputs.put("0", IotaType.serialize(ListIota(list0)))
                    inputs.put("1",IotaType.serialize(ListIota(list1)))
                    val outputs = CompoundTag()
                    outputs.put("0",IotaType.serialize(ListIota(list2)))
                    outputs.put("1",IotaType.serialize(ListIota(list3)))
                    stack.putTag(ItemTruthCrystal.INPUT, inputs)
                    stack.putTag(ItemTruthCrystal.OUTPUT, outputs)
                }
            },
            0,
            listOf()
        )
    }
}