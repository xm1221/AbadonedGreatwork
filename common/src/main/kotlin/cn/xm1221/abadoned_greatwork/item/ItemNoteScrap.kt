package cn.xm1221.abadoned_greatwork.item

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.item.IotaHolderItem
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NoteIota
import dev.architectury.platform.Platform
import net.fabricmc.api.EnvType
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * 笔记残页：携带一条完整 [NoteEntry]（打包为 [NoteIota]）的分享载体。
 * 实现 [IotaHolderItem]，兼容 Hex Casting 的 iota 读写法术；右键可预览并导入个人笔记库（不消耗残页）。
 */
class ItemNoteScrap(props: Properties) : Item(props), IotaHolderItem {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val entry = getEntry(stack)
        if (entry == null) {
            if (level.isClientSide) {
                player.displayClientMessage(Component.translatable("abadoned_greatwork.notes.scrap_empty"), true)
            }
            return InteractionResultHolder.pass(stack)
        }
        if (Platform.getEnv() == EnvType.CLIENT && level.isClientSide) {
            ClientNoteLibrary.openScrapPreviewHook?.invoke(entry, hand == InteractionHand.OFF_HAND)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val entry = getEntry(stack)
        if (entry != null) {
            val title = entry.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
            tooltip.add(
                Component.translatable("abadoned_greatwork.notes.scrap_title", title)
                    .withStyle(ChatFormatting.AQUA)
            )
            if (entry.author.isNotBlank()) {
                tooltip.add(
                    Component.translatable("abadoned_greatwork.notes.scrap_author", entry.author, entry.pages.size)
                        .withStyle(ChatFormatting.GRAY)
                )
            }
            tooltip.add(
                Component.translatable("abadoned_greatwork.notes.scrap_use_hint")
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
        } else {
            tooltip.add(
                Component.translatable("abadoned_greatwork.notes.scrap_empty")
                    .withStyle(ChatFormatting.GRAY)
            )
        }
        IotaHolderItem.appendHoverText(this, stack, tooltip, flag)
        super.appendHoverText(stack, level, tooltip, flag)
    }

    // ---- IotaHolderItem ----

    override fun readIotaTag(stack: ItemStack): CompoundTag? {
        val tag = stack.tag ?: return null
        return if (tag.contains(TAG_NOTE)) tag.getCompound(TAG_NOTE) else null
    }

    override fun writeable(stack: ItemStack): Boolean = true

    override fun canWrite(stack: ItemStack, iota: Iota?): Boolean =
        iota == null || iota is NoteIota

    override fun writeDatum(stack: ItemStack, iota: Iota?) {
        if (iota != null) {
            stack.getOrCreateTag().put(TAG_NOTE, IotaType.serialize(iota))
        } else {
            stack.removeTagKey(TAG_NOTE)
        }
    }

    companion object {
        const val TAG_NOTE = "note"

        fun setEntry(stack: ItemStack, entry: NoteEntry) {
            stack.getOrCreateTag().put(TAG_NOTE, IotaType.serialize(NoteIota(entry)))
        }

        fun getEntry(stack: ItemStack): NoteEntry? {
            val tag = stack.tag ?: return null
            if (!tag.contains(TAG_NOTE)) return null
            val serialized = tag.getCompound(TAG_NOTE)
            if (IotaType.getTypeFromTag(serialized) != NoteIota.TYPE) return null
            val dataTag = serialized.get(HexIotaTypes.KEY_DATA) as? CompoundTag ?: return null
            return NoteEntry.fromTag(dataTag)
        }
    }
}
