package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapBadOffhandItem
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
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
        if (item is ItemTruthCrystal) {
            if (caster !is Player) throw MishapBadCaster()
            val text = ItemTruthCrystal.getTooltip(itemstack)
            if (text != null && text.isNotEmpty()) {
                val longKey = "$text.long"
                val longComp = Component.translatable(longKey)
                // 若无 long 翻译则回退使用 short 描述
                val message = if (longComp.string != longKey) longComp else Component.translatable(text)
                caster.sendSystemMessage(message.copy().withStyle(ChatFormatting.GOLD))
            } else {
                caster.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.tooltip").withStyle(ChatFormatting.GRAY)
                )
            }

            val limit = ItemTruthCrystal.getLengthLimit(itemstack)
            if (limit > 0) {
                caster.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.length", limit).withStyle(ChatFormatting.AQUA)
                )
            }

            val serverLevel = env.world as? ServerLevel
            if (serverLevel != null) {
                val testCount = ItemTruthCrystal.getTestCount(itemstack).coerceAtLeast(
                    if (ItemTruthCrystal.getInputAt(itemstack, serverLevel, 0) != null) 2 else 0
                )
                val previewCount = minOf(testCount, 2)
                for (i in 0 until previewCount) {
                    val inputIotas = ItemTruthCrystal.getInputAt(itemstack, serverLevel, i)?.toList()
                    val outputIotas = ItemTruthCrystal.getOutputAt(itemstack, serverLevel, i)?.toList()
                    if (inputIotas != null) {
                        val inComp = Component.translatable("text.abadoned_greatwork.crystal.input_$i")
                            .append(" ")
                            .append(ListIota(inputIotas).display())
                            .withStyle(ChatFormatting.DARK_GREEN)
                        caster.sendSystemMessage(inComp)
                    }
                    if (outputIotas != null) {
                        val outComp = Component.translatable("text.abadoned_greatwork.crystal.output_$i")
                            .append(" ")
                            .append(ListIota(outputIotas).display())
                            .withStyle(ChatFormatting.GREEN)
                        caster.sendSystemMessage(outComp)
                    }
                }
            }

            return listOf()
        }
        throw MishapBadOffhandItem.of(itemstack, "truth_crystal")
    }
}