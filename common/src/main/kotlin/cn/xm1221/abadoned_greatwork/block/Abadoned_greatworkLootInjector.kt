package cn.xm1221.abadoned_greatwork.block

import cn.xm1221.abadoned_greatwork.api.ItemComponents
import dev.architectury.event.events.common.LootEvent
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.entries.LootItem
import net.minecraft.world.level.storage.loot.functions.SetCustomDataFunction
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue

/**
 * 将预定义谜题真理水晶注入 vanilla 箱子战利品表及本模组结构战利品表。
 */
object Abadoned_greatworkLootInjector {

    /** 结构箱子战利品表（位于 minecraft:chests/ 下） */
    private val CHESTS = listOf(
        "abadoned_akasha",
        "abadoned_greatwork_room",
        "ruined_circles_nether",
        "ruined_circles_overworld",
        "ruined_circles_shulk",
        "ruined_circles_end",
        "ruined_circles_desert",
    )

    fun register() {
        LootEvent.MODIFY_LOOT_TABLE.register { id, context, _ ->
            val loc = id.location()
            if (loc.namespace != "minecraft" || loc.path !in CHESTS.map { "chests/$it" })
                return@register

            val stacks = TruthCrystalPuzzles.ALL
            if (stacks.isEmpty()) return@register

            val pool = LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1f))

            for (stack in stacks) {
                val entry = LootItem.lootTableItem(stack.item).setWeight(1)
                val custom = ItemComponents.customOrNull(stack)
                if (custom != null) {
                    entry.apply(SetCustomDataFunction.setCustomData(custom))
                }
                pool.add(entry)
            }

            context.addPool(pool)
        }
    }
}
