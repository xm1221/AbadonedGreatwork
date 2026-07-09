package cn.xm1221.abadoned_greatwork.block

import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

/**
 * 预定义谜题库。每个谜题自动生成 3 个变体（对应 3 种真理水晶）。
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

    val ALL: List<ItemStack> by lazy {
        Abadoned_greatworkRiddleLoader.riddles.flatMap { riddle ->
            listOf(
                createStack(riddle, 0),
                createStack(riddle, 1),
                createStack(riddle, 2),
            )
        }.distinctBy { "${it.item}${it.tag}" }
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
            riddle.input0Tag()?.let { inputTag.put("0", it) }
            riddle.input1Tag()?.let { inputTag.put("1", it) }
            tag.put(ItemTruthCrystal.INPUT, inputTag)

            val outputTag = CompoundTag()
            riddle.output0Tag()?.let { outputTag.put("0", it) }
            riddle.output1Tag()?.let { outputTag.put("1", it) }
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
