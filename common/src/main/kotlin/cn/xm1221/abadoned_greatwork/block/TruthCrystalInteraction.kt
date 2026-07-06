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
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams

/**
 * 真理水晶与 IotaHolderItem 的交互验证逻辑。
 *
 * ## 完整流程
 * 1. 长度检查 → 两轮输入/输出验证
 * 2. eval 模式：等价性检查
 * 3. 全部通过：
 *    - 若水晶带有合成标记 → 不消耗、无奖励，提示"练习模式"
 *    - 否则 → 消耗水晶，按 variant 战利品表给予奖励
 */
object TruthCrystalInteraction {

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

        // 第一轮测试
        if (!runTest(crystalStack, heldList, level, player, useFirst = true)) {
            return InteractionResult.FAIL
        }

        // 第二轮测试
        if (!runTest(crystalStack, heldList, level, player, useFirst = false)) {
            return InteractionResult.FAIL
        }

        // eval 模式：等价性检查
        val mode = ItemTruthCrystal.getMode(crystalStack)
        if (mode != null && mode == "eval") {
            if (!runEvalCheck(crystalStack, heldList, level, player)) {
                return InteractionResult.FAIL
            }
        }

        // 全部通过 → 处理消耗/奖励
        return onSuccess(be, crystalStack, player, level)
    }

    /**
     * 验证通过后的处理。
     * 合成品（is_crafted）不消耗无奖励；普通品消耗并发放变体对应的战利品。
     */
    private fun onSuccess(
        be: TruthCrystalBlockEntity,
        crystalStack: ItemStack,
        player: Player,
        level: ServerLevel,
    ): InteractionResult {
        if (ItemTruthCrystal.isCrafted(crystalStack)) {
            // 合成品：练习模式，不消耗
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.success_crafted")
            )
            be.setItem(0, ItemStack.EMPTY)
            return InteractionResult.SUCCESS
        }

        // 普通品：消耗并给奖励
        val crystalItem = crystalStack.item as ItemTruthCrystal
        val lootTableId = crystalItem.getLootTable()

        // 发放战利品
        val lootTable = level.server.lootData.getLootTable(lootTableId)
        val lootParams = LootParams.Builder(level)
            .withParameter(LootContextParams.THIS_ENTITY, player)
            .withParameter(LootContextParams.ORIGIN, player.position())
            .create(LootContextParamSets.GIFT)
        val loot = lootTable.getRandomItems(lootParams)

        for (stack in loot) {
            player.drop(stack, false)
        }

        // 消耗水晶
        be.setItem(0, ItemStack.EMPTY)

        player.sendSystemMessage(
            Component.translatable("text.abadoned_greatwork.crystal.success_reward")
        )
        return InteractionResult.SUCCESS
    }

    /**
     * 执行单轮验证：压入 input → 执行 held → 比较 output。
     */
    private fun runTest(
        crystalStack: ItemStack,
        heldIotas: List<Iota>,
        level: ServerLevel,
        player: Player,
        useFirst: Boolean,
    ): Boolean {
        val inputIotas = ItemTruthCrystal.getInput(crystalStack, level, useFirst)
            ?.toList() ?: run {
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.no_input")
                )
                return false
            }

        val expectedOutput = ItemTruthCrystal.getOutput(crystalStack, level, useFirst)
            ?.toList() ?: run {
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.no_output")
                )
                return false
            }

        val env = TestCastingEnv(level, player, player.position())
        val image = CastingImage().copy(stack = inputIotas.toMutableList())
        val vm = CastingVM(image, env)

        vm.queueExecuteAndWrapIotas(heldIotas, level)

        if (!iotaListsMatch(vm.image.stack, expectedOutput)) {
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.wrong")
            )
            return false
        }

        return true
    }

    /**
     * eval 模式额外检查：手持法术和水晶存储法术执行结果是否一致。
     */
    private fun runEvalCheck(
        crystalStack: ItemStack,
        heldList: List<Iota>,
        level: ServerLevel,
        player: Player,
    ): Boolean {
        val inputIotas = ItemTruthCrystal.getInput(crystalStack, level, true)
            ?.toList() ?: return false

        val env = TestCastingEnv(level, player, player.position())
        val image = CastingImage().copy(stack = inputIotas.toMutableList())

        // 执行手持法术
        val vm = CastingVM(image, env)
        vm.queueExecuteAndWrapIotas(heldList, level)

        // 执行水晶中存储的法术
        val crystalItem = crystalStack.item as IotaHolderItem
        val storedIota = crystalItem.readIota(crystalStack, level) ?: return false
        val storedList = if (storedIota is ListIota) storedIota.list.toList() else listOf(storedIota)

        val otherImage = CastingImage().copy(stack = inputIotas.toMutableList())
        val otherVm = CastingVM(otherImage, env)
        otherVm.queueExecuteAndWrapIotas(storedList, level)

        if (!iotaListsMatch(vm.image.stack, otherVm.image.stack)) {
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.wrong")
            )
            return false
        }

        return true
    }

    private fun iotaListsMatch(actual: List<Iota>, expected: List<Iota>): Boolean {
        if (actual.size != expected.size) return false
        for (i in actual.indices) {
            if (!Iota.tolerates(actual[i], expected[i])) return false
        }
        return true
    }
}
