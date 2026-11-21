package cn.xm1221.abadoned_greatwork.item

import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import dev.architectury.platform.Platform
import net.fabricmc.api.EnvType
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * 紫水晶笔：右键打开玩家的笔记库与贴图驱动编辑器 GUI。
 */
class ItemAmethystPen(props: Properties) : Item(props) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (Platform.getEnv() == EnvType.CLIENT && level.isClientSide) {
            ClientNoteLibrary.openLibraryScreenHook?.invoke()
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    companion object {
        fun hasPen(player: Player): Boolean {
            if (player.isCreative) return true
            val pen = Abadoned_greatworkItems.AMETHYST_PEN.value
            if (player.mainHandItem.`is`(pen) || player.offhandItem.`is`(pen)) return true
            val inv = player.inventory
            for (i in 0 until inv.containerSize) {
                if (inv.getItem(i).`is`(pen)) return true
            }
            return false
        }
    }
}
