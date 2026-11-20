package cn.xm1221.abadoned_greatwork.ponder.sim

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.ponder.data.PonderIotaCodec
import cn.xm1221.abadoned_greatwork.ponder.data.PonderPlaybackBundle
import cn.xm1221.abadoned_greatwork.ponder.data.PonderSceneDef
import cn.xm1221.abadoned_greatwork.ponder.data.PonderSceneLoader
import cn.xm1221.abadoned_greatwork.ponder.data.PonderStepDef
import cn.xm1221.abadoned_greatwork.ponder.data.ResolvedCallout
import cn.xm1221.abadoned_greatwork.ponder.data.ResolvedKnowledgeCard
import cn.xm1221.abadoned_greatwork.ponder.data.StepSnapshot
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/**
 * 服务端一次性思索预模拟器。
 *
 * 在安全环境 [PonderCastEnv] 中将场景步骤顺序执行一遍并记录每一步的不可变 [StepSnapshot]（Tag-Only），
 * 使客户端无需每步联网即可在时间轴上做 O(1) 前进、后退、拖拽跳转与重播。
 */
object PonderPreSimulator {

    const val COLOR_EVALUATED = 0xFF64C8FF.toInt()
    const val COLOR_ERRORED = 0xFFFF6B6B.toInt()

    data class SandboxExecResult(
        val imageNbt: CompoundTag,
        val resolutionType: String,
        val consoleLines: List<Component>,
        val mishapText: Component?,
    )

    fun simulate(scene: PonderSceneDef, player: ServerPlayer): PonderPlaybackBundle {
        val world = player.serverLevel()
        val presets = PonderSceneLoader.presetIotas
        val env = PonderCastEnv(world, player)
        val vm = CastingVM(CastingImage(), env)

        val resolvedCards = scene.knowledgeCards.map { card ->
            ResolvedKnowledgeCard(
                title = card.title,
                body = card.body,
                inlineIotaTags = card.inlineIotas.mapNotNull { PonderIotaCodec.resolveString(it, presets) },
            )
        }

        val snapshots = ArrayList<StepSnapshot>(scene.steps.size)
        for ((index, step) in scene.steps.withIndex()) {
            val prevStackSize = vm.image.stack.size
            env.capturedMessages.clear()

            val displayPat = resolveDisplayPattern(step)
            val execPat = resolveExecPattern(step)
            var resolutionType = when (step.type) {
                "push" -> "PUSH"
                "peek" -> "PEEK"
                "clear" -> "CLEAR"
                "note" -> "NOTE"
                else -> "EVALUATED"
            }

            when (step.type) {
                "push" -> {
                    val pushTag = PonderIotaCodec.resolveElement(step.pushRaw, presets)
                    if (pushTag != null) {
                        val iota = cn.xm1221.abadoned_greatwork.api.iotaFromNbt(pushTag, world.registryAccess())
                        if (iota != null) {
                            vm.image = vm.image.copy(stack = vm.image.stack.appended(iota))
                        }
                    }
                }
                "push_in" -> {
                    val pushTag = PonderIotaCodec.resolveElement(step.pushRaw, presets)
                    if (pushTag != null) {
                        val iota = cn.xm1221.abadoned_greatwork.api.iotaFromNbt(pushTag, world.registryAccess())
                        if (iota != null) {
                            val res = vm.queueExecuteAndWrapIota(iota, world)
                            resolutionType = res.resolutionType.name
                        }
                    }
                }
                "peek" -> {
                    val removeTargets = if (step.peekIndices.isNotEmpty()) {
                        step.peekIndices
                    } else if (step.peekIndex >= 0) {
                        listOf(step.peekIndex)
                    } else {
                        emptyList()
                    }
                    if (removeTargets.isNotEmpty()) {
                        val mutable = vm.image.stack.toMutableList()
                        val realIndices = removeTargets
                            .map { mutable.size - 1 - it }
                            .filter { it in mutable.indices }
                            .distinct()
                            .sortedDescending()
                        for (realIdx in realIndices) {
                            mutable.removeAt(realIdx)
                        }
                        vm.image = vm.image.copy(stack = at.petrak.hexcasting.api.utils.TreeList.from(mutable))
                    }
                }
                "clear", "note" -> {
                    // clear 仅由客户端画布在绘制前清空旧图案，note 为纯讲解节点，均不改动 VM 栈
                }
                else -> {
                    if (execPat != null) {
                        val res = vm.queueExecuteAndWrapIota(PatternIota(execPat), world)
                        resolutionType = res.resolutionType.name
                    }
                }
            }

            val stackTags = vm.image.stack.map { cn.xm1221.abadoned_greatwork.api.iotaToNbt(it, world.registryAccess()) }.toList()
            val ravenmindTag = vm.image.getComponent(at.petrak.hexcasting.common.lib.hex.HexImageComponents.RAVENMIND.get())
                ?.let { cn.xm1221.abadoned_greatwork.api.iotaToNbt(it, world.registryAccess()) }
            val newStackSize = stackTags.size
            val poppedCount = (prevStackSize - newStackSize).coerceAtLeast(0)
            val pushedCount = (newStackSize - prevStackSize).coerceAtLeast(0)

            val resolvedColor = when {
                step.color != -1 -> step.color
                (resolutionType == ResolvedPatternType.ESCAPED.name || step.type == "push") && scene.escapedColor != -1 -> scene.escapedColor
                else -> -1
            }

            val parenthesizedTags = vm.image.parenthesized.map {
                cn.xm1221.abadoned_greatwork.api.iotaToNbt(it.iota, world.registryAccess())
            }.toList()

            val isErrored = resolutionType == ResolvedPatternType.ERRORED.name
            val captured = env.capturedMessages.toList()
            val mishapText = if (isErrored) captured.lastOrNull() else null
            val consoleLines = if (isErrored && captured.isNotEmpty()) captured.dropLast(1) else captured

            val resolvedCallout = step.callout?.let { c ->
                ResolvedCallout(
                    anchor = c.anchor,
                    anchorIndex = c.anchorIndex,
                    anchorQ = c.anchorQ,
                    anchorR = c.anchorR,
                    accentColor = c.accentColor,
                    text = c.text,
                    inlineIotaTags = c.inlineIotas.mapNotNull { PonderIotaCodec.resolveString(it, presets) },
                    pause = c.pause,
                )
            }

            val stepTitle = resolveStepTitle(step)
            val cursorId = step.cursorItem ?: scene.cursorItem
            val clearBefore = step.clearBeforeOverride ?: scene.clearBefore

            snapshots.add(
                StepSnapshot(
                    stepIndex = index,
                    stepType = step.type,
                    actionId = step.action,
                    title = stepTitle,
                    chapter = step.chapter,
                    displaySig = displayPat?.anglesSignature() ?: "",
                    displayStartDir = displayPat?.startDir?.name ?: step.startDir,
                    hasDisplayPattern = displayPat != null,
                    originQ = step.originQ,
                    originR = step.originR,
                    clearCanvasBefore = clearBefore,
                    patternColor = resolvedColor,
                    cursorItemId = cursorId,
                    strokeTicks = if (displayPat != null) step.strokeTicks else 1,
                    holdTicks = step.holdTicks,
                    stackTags = stackTags,
                    poppedCount = poppedCount,
                    pushedCount = pushedCount,
                    openParens = vm.image.parenCount,
                    parenthesizedTags = parenthesizedTags,
                    escapeNext = vm.image.escapeNext,
                    ravenmindTag = ravenmindTag,
                    opsConsumed = vm.image.opsConsumed,
                    imageNbt = cn.xm1221.abadoned_greatwork.api.castingImageToNbt(vm.image, world.registryAccess()),
                    resolutionType = resolutionType,
                    consoleLines = consoleLines,
                    mishapText = mishapText,
                    callout = resolvedCallout,
                )
            )
        }

        return PonderPlaybackBundle(
            sceneId = scene.id,
            title = scene.title,
            subtitle = scene.subtitle,
            iconItemId = scene.iconItem,
            hexSize = scene.hexSize,
            escapedColor = scene.escapedColor,
            defaultCursorItemId = scene.cursorItem,
            knowledgeCards = resolvedCards,
            snapshots = snapshots,
        )
    }

    fun executeSandbox(player: ServerPlayer, pattern: HexPattern, imageNbt: CompoundTag): SandboxExecResult {
        val world = player.serverLevel()
        val image = try {
            cn.xm1221.abadoned_greatwork.api.castingImageFromNbt(imageNbt, world.registryAccess())
        } catch (_: Exception) {
            CastingImage()
        }
        val env = PonderCastEnv(world, player)
        val vm = CastingVM(image, env)
        val res = vm.queueExecuteAndWrapIota(PatternIota(pattern), world)
        val resolutionName = res.resolutionType.name
        val isErrored = res.resolutionType == ResolvedPatternType.ERRORED
        val captured = env.capturedMessages.toList()
        val mishapText = if (isErrored) captured.lastOrNull() else null
        val consoleLines = if (isErrored && captured.isNotEmpty()) captured.dropLast(1) else captured
        return SandboxExecResult(
            imageNbt = cn.xm1221.abadoned_greatwork.api.castingImageToNbt(vm.image, world.registryAccess()),
            resolutionType = resolutionName,
            consoleLines = consoleLines,
            mishapText = mishapText,
        )
    }

    private fun resolveStepTitle(step: PonderStepDef): String {
        if (step.title.isNotEmpty()) return step.title
        if (step.action.isNotEmpty()) return "hexcasting.action.${step.action}"
        return ""
    }

    private fun resolveDisplayPattern(step: PonderStepDef): HexPattern? {
        if (step.pattern.isNotEmpty()) {
            return runCatching {
                patternFromAngles(step.pattern, HexDir.valueOf(step.startDir.uppercase()))
            }.getOrNull()
        }
        if (step.action.isNotEmpty()) {
            val rl = ResourceLocation.tryParse(step.action) ?: return null
            return HexActions.REGISTRY.get(rl)?.prototype()
        }
        return null
    }

    private fun resolveExecPattern(step: PonderStepDef): HexPattern? {
        if (step.action.isNotEmpty()) {
            val rl = ResourceLocation.tryParse(step.action) ?: return null
            val proto = HexActions.REGISTRY.get(rl)?.prototype()
            if (proto != null) return proto
        }
        if (step.pattern.isNotEmpty()) {
            return runCatching {
                patternFromAngles(step.pattern, HexDir.valueOf(step.startDir.uppercase()))
            }.getOrNull()
        }
        return null
    }
}
