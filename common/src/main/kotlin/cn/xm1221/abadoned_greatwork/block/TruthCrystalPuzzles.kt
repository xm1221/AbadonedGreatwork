package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.casting.iota.BooleanIota
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.ListIota
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.world.item.ItemStack

/**
 * 预定义谜题库。每个谜题自动生成 3 个变体（对应 3 种真理水晶）。
 */
object TruthCrystalPuzzles {

    data class Puzzle(
        val name: String,
        val input0: ListIota,
        val input1: ListIota,
        val output0: ListIota,
        val output1: ListIota,
        val lengthLimit: Int,
    )

    val Add = Puzzle(
        createLangKey("add"),
        ListIota(listOf(DoubleIota(0.0), DoubleIota(1.0))),
        ListIota(listOf(DoubleIota(2.0), DoubleIota(4.0))),
        ListIota(listOf(DoubleIota(1.0))),
        ListIota(listOf(DoubleIota(6.0))),
        3,
    )

    val Bigger = Puzzle(
        createLangKey("bigger"),
        ListIota(listOf(DoubleIota(0.0), DoubleIota(1.0))),
        ListIota(listOf(DoubleIota(3.0),DoubleIota(2.0))),
        ListIota(listOf(DoubleIota(1.0))),
        ListIota(listOf(DoubleIota(3.0))),
        5
    )



    /** 所有预定义谜题 × 3 变体的 ItemStack */
    val ALL: List<ItemStack> by lazy {
        listOf(Add, Bigger).flatMap { puzzle ->
            listOf(
                createStack(puzzle, 0),
                createStack(puzzle, 1),
                createStack(puzzle, 2),
            )
        }
    }

    private val ITEMS by lazy {
        listOf(
            Abadoned_greatworkItems.TRUTH_CRYSTAL_0,
            Abadoned_greatworkItems.TRUTH_CRYSTAL_1,
            Abadoned_greatworkItems.TRUTH_CRYSTAL_2,
        )
    }

    fun createStack(puzzle: Puzzle, variant: Int): ItemStack {
        val stack = ItemStack(ITEMS[variant % 3].value)
        ItemTruthCrystal.setInput(stack, puzzle.input0, puzzle.input1)
        ItemTruthCrystal.setOutput(stack, puzzle.output0, puzzle.output1)
        ItemTruthCrystal.setLengthLimit(stack, puzzle.lengthLimit)
        ItemTruthCrystal.setTooltip(stack, puzzle.name)
        ItemTruthCrystal.setMode(stack, "normal")
        return stack
    }

    private fun createLangKey(name: String): String =
        "text.abadoned_greatwork.riddles.$name"
}
