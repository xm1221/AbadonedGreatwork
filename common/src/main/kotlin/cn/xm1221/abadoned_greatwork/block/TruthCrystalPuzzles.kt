package cn.xm1221.abadoned_greatwork.block

import cn.xm1221.abadoned_greatwork.api.ItemComponents
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
            val raw = Abadoned_greatworkRiddleLoader.riddles.flatMap { riddle ->
                val variants = when (riddle.variant) {
                    0 -> listOf(0)
                    1 -> listOf(1)
                    2 -> listOf(2)
                    else -> listOf(0, 1, 2)
                }
                variants.mapNotNull { v ->
                    if (riddle.isRawNbt && riddle.nbtTag() == null) null else createStack(riddle, v)
                }
            }
            val list = ArrayList<ItemStack>(raw.size)
            for (candidate in raw) {
                if (!ItemComponents.hasCustom(candidate)) continue
                if (list.none { ItemStack.isSameItemSameComponents(it, candidate) }) {
                    list.add(candidate)
                }
            }
            cachedStacks = list
            return list
        }

    fun invalidateCache() {
        cachedStacks = null
    }

    fun createStack(riddle: Abadoned_greatworkRiddleLoader.RawRiddle, variant: Int): ItemStack {
        val stack = ItemStack(ITEMS[variant % 3].value)

        if (riddle.isRawNbt) {
            riddle.nbtTag()?.let { raw ->
                val merged = ItemComponents.copyCustom(stack)
                merged.merge(raw)
                ItemComponents.setCustom(stack, merged)
            }
        } else {
            val tag = ItemComponents.copyCustom(stack)

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
            ItemComponents.setCustom(stack, tag)
        }

        if (!riddle.ponder_hints.isNullOrEmpty()) {
            ItemTruthCrystal.setPonderHints(stack, riddle.ponder_hints)
        }

        return stack
    }
}
