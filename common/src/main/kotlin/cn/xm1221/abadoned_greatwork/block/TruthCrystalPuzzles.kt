package cn.xm1221.abadoned_greatwork.block

import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * 预定义谜题库。根据谜题的 variant 字段生成对应变体的水晶（若未指定则生成全部 3 种变体）。
 * 谜题数据来自 [Abadoned_greatworkRiddleLoader]（`data/<ns>/riddles/`）。
 */
object TruthCrystalPuzzles {

    private val ITEMS by lazy {
        listOf(
            Abadoned_greatworkItems.TRUTH_CRYSTAL_0,
            Abadoned_greatworkItems.TRUTH_CRYSTAL_1,
            Abadoned_greatworkItems.TRUTH_CRYSTAL_2,
        )
    }

    private var cachedStacks: List<ItemStack>? = null

    val ALL: List<ItemStack>
        get() {
            cachedStacks?.let { return it }
            val list = Abadoned_greatworkRiddleLoader.riddles.flatMap { riddle ->
                when (riddle.variant) {
                    0 -> listOf(createStack(riddle, 0))
                    1 -> listOf(createStack(riddle, 1))
                    2 -> listOf(createStack(riddle, 2))
                    else -> listOf(
                        createStack(riddle, 0),
                        createStack(riddle, 1),
                        createStack(riddle, 2),
                    )
                }
            }.distinctBy { "${it.item}${it.tag}" }
            cachedStacks = list
            return list
        }

    fun invalidateCache() {
        cachedStacks = null
    }

    fun createStack(riddle: Abadoned_greatworkRiddleLoader.RawRiddle, variant: Int): ItemStack {
        val stack = ItemStack(ITEMS[variant % 3].value)

        if (riddle.isRawNbt) {
            // 完整 NBT 模式：直接合并 SNBT 到物品栈
            riddle.nbtTag()?.let { stack.orCreateTag.merge(it) }
        } else {
            // 结构化字段模式
            val tag = stack.orCreateTag

            val inputTag = CompoundTag()
            val inputTags = riddle.getAllInputTags()
            inputTags.forEachIndexed { idx, it -> inputTag.put(idx.toString(), it) }
            tag.put(ItemTruthCrystal.INPUT, inputTag)

            val outputTag = CompoundTag()
            val outputTags = riddle.getAllOutputTags()
            outputTags.forEachIndexed { idx, it -> outputTag.put(idx.toString(), it) }
            tag.put(ItemTruthCrystal.OUTPUT, outputTag)

            tag.putInt(ItemTruthCrystal.LENGTH_LIMIT, riddle.length_limit)
            tag.putString(ItemTruthCrystal.TOOLTIP, riddle.name_key)
            tag.putString(ItemTruthCrystal.MODE, "normal")
            tag.putBoolean(ItemTruthCrystal.IS_CRAFTED, false)
            tag.putBoolean(ItemTruthCrystal.CAN_WRITE, false)
        }

        return stack
    }
}
