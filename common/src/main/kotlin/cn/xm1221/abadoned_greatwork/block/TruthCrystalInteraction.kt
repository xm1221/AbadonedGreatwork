package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.item.IotaHolderItem
import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import cn.xm1221.abadoned_greatwork.casting.TestCastingEnv
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams

/**
 * 真理水晶与 IotaHolderItem 的交互验证逻辑。
 *
 * ## 完整流程
 * 1. 递归长度检查 → 多轮输入/输出验证
 * 2. eval 模式：等价性多轮检查
 * 3. 全部通过：
 *    - 若水晶带有合成标记 → 不消耗水晶，播放紫水晶音效，保留水晶在基座上
 *    - 否则 → 消耗水晶，按 variant 战利品表给予奖励，播放通关音效与粒子
 */
object TruthCrystalInteraction {

    private fun grant(player: Player, name: String) {
        val sp = player as? ServerPlayer ?: return
        val id = ResourceLocation("abadoned_greatwork", name)
        val adv = sp.server.advancements.getAdvancement(id) ?: return
        sp.advancements.award(adv, "impossible")
    }

    fun handle(
        be: TruthCrystalBlockEntity,
        player: Player,
        level: ServerLevel,
        heldStack: ItemStack,
    ): InteractionResult {
        val crystalStack = be.item
        if (crystalStack.item !is ItemTruthCrystal) return InteractionResult.PASS

        val heldItem = heldStack.item as? IotaHolderItem ?: return InteractionResult.PASS

        // 进度：首次尝试
        grant(player, "attempt_puzzle")

        // 读取手持物品中的 iota
        val heldIota = heldItem.readIota(heldStack, level) ?: return InteractionResult.PASS
        val heldList = if (heldIota is ListIota) heldIota.list.toList() else listOf(heldIota)

        // 检查长度限制（递归统计所有子项，杜绝嵌套列表作弊）
        val limit = ItemTruthCrystal.getLengthLimit(crystalStack)
        val totalLength = ItemTruthCrystal.countTotalIotas(heldList)
        if (limit > 0 && totalLength > limit) {
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.length_exceed", limit)
            )
            level.playSound(null, be.blockPos, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f)
            level.sendParticles(ParticleTypes.SMOKE, be.blockPos.x + 0.5, be.blockPos.y + 1.1, be.blockPos.z + 0.5, 10, 0.15, 0.15, 0.15, 0.02)
            return InteractionResult.FAIL
        }

        val mode = ItemTruthCrystal.getMode(crystalStack)
        val testCount = ItemTruthCrystal.getTestCount(crystalStack).coerceAtLeast(
            if (ItemTruthCrystal.getInputAt(crystalStack, level, 0) != null) 2 else 0
        )

        if (mode == "eval") {
            // eval 模式：等价性检查
            if (!runEvalCheck(crystalStack, heldList, level, player, be, testCount)) {
                return InteractionResult.FAIL
            }
        } else {
            if (testCount == 0) {
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.no_input")
                )
                return InteractionResult.FAIL
            }
            for (round in 0 until testCount) {
                if (!runTest(crystalStack, heldList, level, player, be, round)) {
                    return InteractionResult.FAIL
                }
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
        grant(player, "solve_puzzle")
        if (ItemTruthCrystal.isCrafted(crystalStack)) {
            // 合成品：练习模式，不消耗，保留在基座上
            player.sendSystemMessage(
                Component.translatable("text.abadoned_greatwork.crystal.success_crafted")
            )
            level.playSound(null, be.blockPos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, 1.4f)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, be.blockPos.x + 0.5, be.blockPos.y + 1.2, be.blockPos.z + 0.5, 15, 0.2, 0.2, 0.2, 0.05)
            return InteractionResult.SUCCESS
        }

        // 普通品：消耗并给奖励
        val crystalItem = crystalStack.item as ItemTruthCrystal
        val lootTableId = crystalItem.lootTable

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

        level.playSound(null, be.blockPos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, 1.2f)
        level.playSound(null, be.blockPos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.0f)
        level.sendParticles(ParticleTypes.END_ROD, be.blockPos.x + 0.5, be.blockPos.y + 1.2, be.blockPos.z + 0.5, 20, 0.25, 0.25, 0.25, 0.05)

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
        be: TruthCrystalBlockEntity,
        round: Int,
    ): Boolean {
        val inputIotas = ItemTruthCrystal.getInputAt(crystalStack, level, round)
            ?.toList() ?: run {
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.no_input")
                )
                return false
            }

        val expectedOutput = ItemTruthCrystal.getOutputAt(crystalStack, level, round)
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
            level.playSound(null, be.blockPos, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f)
            level.sendParticles(ParticleTypes.SMOKE, be.blockPos.x + 0.5, be.blockPos.y + 1.1, be.blockPos.z + 0.5, 10, 0.15, 0.15, 0.15, 0.02)
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
        be: TruthCrystalBlockEntity,
        testCount: Int,
    ): Boolean {
        val crystalItem = crystalStack.item as? IotaHolderItem ?: return false
        val storedIota = crystalItem.readIota(crystalStack, level) ?: return false
        val storedList = if (storedIota is ListIota) storedIota.list.toList() else listOf(storedIota)

        val rounds = if (testCount > 0) testCount else 1
        for (round in 0 until rounds) {
            val inputIotas = ItemTruthCrystal.getInputAt(crystalStack, level, round)?.toList() ?: emptyList()

            val env1 = TestCastingEnv(level, player, player.position())
            val image1 = CastingImage().copy(stack = inputIotas.toMutableList())
            val vm1 = CastingVM(image1, env1)
            vm1.queueExecuteAndWrapIotas(heldList, level)

            val env2 = TestCastingEnv(level, player, player.position())
            val image2 = CastingImage().copy(stack = inputIotas.toMutableList())
            val vm2 = CastingVM(image2, env2)
            vm2.queueExecuteAndWrapIotas(storedList, level)

            if (!iotaListsMatch(vm1.image.stack, vm2.image.stack)) {
                level.playSound(null, be.blockPos, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f)
                level.sendParticles(ParticleTypes.SMOKE, be.blockPos.x + 0.5, be.blockPos.y + 1.1, be.blockPos.z + 0.5, 10, 0.15, 0.15, 0.15, 0.02)
                player.sendSystemMessage(
                    Component.translatable("text.abadoned_greatwork.crystal.wrong")
                )
                return false
            }
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
