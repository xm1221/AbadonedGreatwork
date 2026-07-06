package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.item.IotaHolderItem
import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import cn.xm1221.abadoned_greatwork.casting.TestCastingEnv
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * 真理水晶与 IotaHolderItem 的交互验证逻辑。
 *
 * ## 验证流程
 * 1. 取出手持物品中的所有 iota，检查数量是否超过 ItemTruthCrystal 的 length_limit
 * 2. 取 ItemTruthCrystal input["0"] 压入 VM 栈，执行手持 iota，比较输出与 output["0"]
 * 3. 若匹配，换 input["1"] / output["1"] 再测一次
 * 4. 全部通过则祝贺；任意一步失败则提示错误
 */
object TruthCrystalInteraction {

    /**
     * 执行完整的两轮验证测试。
     *
     * @return [InteractionResult.SUCCESS] 全部通过；[InteractionResult.FAIL] 某步失败
     */
    fun handle(
        be: TruthCrystalBlockEntity,
        player: Player,
        level: ServerLevel,
        heldStack: ItemStack,
    ): InteractionResult {
        val crystalStack = be.item
        if (crystalStack.item !is ItemTruthCrystal) return InteractionResult.PASS

        val heldItem = heldStack.item as? IotaHolderItem ?: return InteractionResult.PASS

        // 读取手持物品中的 iota
        val heldIota = heldItem.readIota(heldStack, level) ?: return InteractionResult.PASS
        val heldList = if (heldIota is ListIota) heldIota.list.toList() else listOf(heldIota)

        // 检查长度限制
        val limit = ItemTruthCrystal.getLengthLimit(crystalStack)
        if (heldList.size > limit) {
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.length_exceed", limit)
            )
            return InteractionResult.FAIL
        }

        // 第一轮测试：key = "0"
        if (!runTest(crystalStack, heldList, level, player, useFirst = true)) {
            return InteractionResult.FAIL
        }

        // 第二轮测试：key = "1"
        if (!runTest(crystalStack, heldList, level, player, useFirst = false)) {
            return InteractionResult.FAIL
        }

        if(ItemTruthCrystal.getMode(crystalStack)!=null) {
            if(ItemTruthCrystal.getMode(crystalStack) == "eval") {
                val inputIotas = ItemTruthCrystal.getInput(crystalStack, level, true)
                    ?.toList() ?: run {
                    player.sendSystemMessage(
                        Component.translatable("text.abadoned_greatwork.crystal.no_input")
                    )
                    return InteractionResult.FAIL
                }
                val env = TestCastingEnv(level, player, player.position())
                val image = CastingImage().copy(stack = inputIotas.toMutableList())
                val vm = CastingVM(image, env)
                val otherVm = CastingVM(image, env)
                val crystal =crystalStack.item as IotaHolderItem

                // 执行手持物品中的 iota
                vm.queueExecuteAndWrapIotas(heldList, level)
                val codes = crystal.readIota(crystalStack,level)
                if(codes != null) {
                    val codelist = if (codes is ListIota) codes.list.toList() else listOf(codes)
                    otherVm.queueExecuteAndWrapIotas(codelist, level)
                }
                if(vm.image.stack == otherVm.image.stack) {
                    player.sendSystemMessage(
                        Component.translatable("text.abadoned_greatwork.crystal.success")
                    )
                    return InteractionResult.SUCCESS
                }

            }
        }

        // 全部通过
        player.sendSystemMessage(
            Component.translatable("text.abadoned_greatwork.crystal.success")
        )
        return InteractionResult.SUCCESS
    }

    /**
     * 执行单轮验证：压入 input → 执行 held → 比较 output。
     *
     * @param useFirst `true` 使用 "0" 字段，`false` 使用 "1" 字段
     */
    private fun runTest(
        crystalStack: ItemStack,
        heldIotas: List<Iota>,
        level: ServerLevel,
        player: Player,
        useFirst: Boolean,
    ): Boolean {
        // 读取输入
        val inputIotas = ItemTruthCrystal.getInput(crystalStack, level, useFirst)
            ?.toList() ?: run {
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.no_input")
                )
                return false
            }

        // 读取期望输出
        val expectedOutput = ItemTruthCrystal.getOutput(crystalStack, level, useFirst)
            ?.toList() ?: run {
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.no_output")
                )
                return false
            }

        // 构建 VM，将输入 iota 预置入栈中
        val env = TestCastingEnv(level, player, player.position())
        val image = CastingImage().copy(stack = inputIotas.toMutableList())
        val vm = CastingVM(image, env)

        // 执行手持物品中的 iota
        vm.queueExecuteAndWrapIotas(heldIotas, level)

        // 比较栈结果与期望输出
        val resultStack = vm.image.stack
        if (!iotaListsMatch(resultStack, expectedOutput)) {
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.wrong")
            )
            return false
        }

        return true
    }

    /**
     * 比较两个 iota 列表是否逐项匹配（使用 [Iota.toleratesOther] 语义相等）。
     */
    private fun iotaListsMatch(actual: List<Iota>, expected: List<Iota>): Boolean {
        if (actual.size != expected.size) return false
        for (i in actual.indices) {
            if (!Iota.tolerates(actual[i], expected[i])) return false
        }
        return true
    }
}
