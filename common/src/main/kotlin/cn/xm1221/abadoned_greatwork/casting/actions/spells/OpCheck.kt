package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapBadOffhandItem
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player

class OpCheck: ConstMediaAction {
    override val argc: Int
    get() = 0

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): List<Iota> {
        val caster = env.castingEntity ?: throw MishapBadCaster()
        val hand = env.otherHand
        val itemstack = caster.getItemInHand(hand)
        val item = itemstack.item
        if(item is ItemTruthCrystal){
            val text = ItemTruthCrystal.getTooltip(itemstack)
            if(text.isNotEmpty() && caster is Player ){
                val message = Component.translatable("$text.long")
                caster.sendSystemMessage(message)
                return listOf()
            }
            else{
                throw MishapBadCaster()
            }
        }
        throw MishapBadOffhandItem.of(itemstack,"truth_crystal")
    }
}