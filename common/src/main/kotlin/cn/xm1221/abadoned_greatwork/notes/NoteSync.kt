package cn.xm1221.abadoned_greatwork.notes

import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteLibrarySyncS2C
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack

/**
 * 服务端笔记同步与纸张消耗辅助。
 */
object NoteSync {

    fun collectExcludedActionIds(): List<String> {
        val greatTag = HexActions.REGISTRY.getTag(HexTags.Actions.REQUIRES_ENLIGHTENMENT)
        val perWorldTag = HexActions.REGISTRY.getTag(HexTags.Actions.PER_WORLD_PATTERN)
        return HexActions.REGISTRY.entrySet()
            .filter { (key, _) ->
                val inGreat = greatTag.isPresent && greatTag.get().stream().anyMatch { it.`is`(key) }
                val inPerWorld = perWorldTag.isPresent && perWorldTag.get().stream().anyMatch { it.`is`(key) }
                inGreat || inPerWorld
            }
            .map { (key, _) -> key.location().toString() }
    }

    fun syncAll(player: ServerPlayer) {
        val lib = NoteLibrary.get(player.server)
        val entries = lib.entries(player.uuid).map { it.toTag() }
        val excluded = collectExcludedActionIds()
        MsgNoteLibrarySyncS2C(entries, excluded).sendToPlayer(player)
    }

    /** 统计玩家背包（含主手、副手、主物品栏）中纸的总数 */
    fun countPaper(player: ServerPlayer): Int {
        var total = 0
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (stack.`is`(Items.PAPER)) {
                total += stack.count
            }
        }
        return total
    }

    /** 从玩家背包中扣除指定数量的纸（优先主物品栏/副手，按槽位顺序扣除） */
    fun consumePaper(player: ServerPlayer, amount: Int): Boolean {
        if (player.isCreative || amount <= 0) return true
        if (countPaper(player) < amount) return false
        var remaining = amount
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            if (remaining <= 0) break
            val stack: ItemStack = inv.getItem(i)
            if (stack.`is`(Items.PAPER)) {
                val take = minOf(remaining, stack.count)
                stack.shrink(take)
                remaining -= take
            }
        }
        inv.setChanged()
        return remaining == 0
    }
}
