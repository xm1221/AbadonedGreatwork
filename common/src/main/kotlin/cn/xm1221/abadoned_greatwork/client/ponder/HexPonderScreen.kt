package cn.xm1221.abadoned_greatwork.client.ponder

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.math.HexCoord
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.utils.coordToPx
import at.petrak.hexcasting.api.utils.pxToCoord
import at.petrak.hexcasting.client.gui.GuiSpellcasting
import at.petrak.hexcasting.client.render.drawPatternFromPoints
import at.petrak.hexcasting.client.render.drawSpot
import at.petrak.hexcasting.client.render.findDupIndices
import at.petrak.hexcasting.common.lib.HexSounds
import cn.xm1221.MieLib.registry.DrawPatternHook
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecS2C
import cn.xm1221.abadoned_greatwork.ponder.data.PonderPlaybackBundle
import cn.xm1221.abadoned_greatwork.ponder.data.StepSnapshot
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec2
import org.lwjgl.glfw.GLFW
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 独立于 Patchouli 的「咒法思索（Hex Ponder）」全屏界面（Native Minecraft + Hex Casting 像素风格）。
 *
 * 核心特性：
 * - 带石板斜面框与紫晶角钉的独立施法演示舞台（与侧栏、底栏明确区隔）；
 * - 逐段流光描画动画 + 可配置物品贴图画笔光标（默认为橡木法杖）；
 * - 通过 MieLib 的 [DrawPatternHook] 注入演示图案颜色；
 * - 左侧 Tag-Only 施法栈、CastingImage 映像态与输出/事故台；
 * - 锚定到画布/栈槽/映像的像素引线浮动提示气泡；
 * - 时间轴跳转、单步前后、倍速、可折叠知识点抽屉与「亲自一试」交互沙盒。
 */
class HexPonderScreen(
    initialBundle: PonderPlaybackBundle,
    private val parentScreen: Screen? = null,
) : Screen(Component.literal(initialBundle.title)) {

    var bundle: PonderPlaybackBundle = initialBundle
        private set

    // 播放状态
    private var currentStepIndex: Int = 0
    private var stepTicks: Float = 0f
    private var playing: Boolean = true
    private var speed: Float = 1.0f
    private var lastSegmentSounded: Int = -1
    private var stepFinishedTriggered: Boolean = false

    // 面板滚动与开关
    private var stackScroll: Int = 0
    private var knowledgeScroll: Int = 0
    private var knowledgeOpen: Boolean = false
    private var hoveredStackTag: CompoundTag? = null

    // 沙盒模式（「亲自一试」）状态
    private var sandboxMode: Boolean = false
    private var sandboxSpellcasting: GuiSpellcasting? = null
    private var sandboxImageNbt: CompoundTag = CompoundTag()
    private var sandboxStackTags: List<CompoundTag> = emptyList()
    private var sandboxRavenmindTag: CompoundTag? = null
    private var sandboxOpenParens: Int = 0
    private var sandboxParenthesizedTags: List<CompoundTag> = emptyList()
    private var sandboxEscapeNext: Boolean = false
    private var sandboxConsoleLines: List<Component> = emptyList()
    private var sandboxMishap: Component? = null
    private var sandboxColorMap: MutableMap<Int, Pair<Int, Int>> = HashMap()
    private var sandboxSessionSeq: Int = 1

    // 布局缓存（全部整数像素网格对齐）
    private var pad = 8
    private var topBarY = 8
    private var topBarH = 26
    private var bottomBarY = 0
    private var bottomBarH = 42
    private var bodyY = 0
    private var bodyH = 0
    private var leftX = 0
    private var leftW = 0
    private var stageX = 0
    private var stageY = 0
    private var stageW = 0
    private var stageH = 0
    private var innerX = 0
    private var innerY = 0
    private var innerW = 0
    private var innerH = 0
    private var knowledgeX = 0
    private var knowledgeW = 0
    private var timelineX = 0
    private var timelineY = 0
    private var timelineW = 0
    private var timelineH = 10

    // 可点击按钮热区
    private data class BtnBox(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val label: String,
        val active: Boolean = false,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) {
        fun contains(mx: Double, my: Double): Boolean =
            mx >= x && mx < x + w && my >= y && my < y + h
    }

    private val buttons = ArrayList<BtnBox>()

    private data class StagePatternLayout(
        val snapIndex: Int,
        val snap: StepSnapshot,
        val pattern: HexPattern,
        val points: List<Vec2>,
        val dupIndices: Set<Int>,
    )

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        super.init()
        computeLayout()
        if (sandboxMode) {
            setupSandboxWidget()
        }
    }

    fun reloadBundle(newBundle: PonderPlaybackBundle) {
        this.bundle = newBundle
        this.currentStepIndex = 0
        this.stepTicks = 0f
        this.playing = true
        this.lastSegmentSounded = -1
        this.stepFinishedTriggered = false
        this.stackScroll = 0
        this.knowledgeScroll = 0
        if (sandboxMode) exitSandbox()
        computeLayout()
    }

    private fun computeLayout() {
        pad = if (width < 430) 4 else 8
        topBarY = pad
        topBarH = 26
        bottomBarH = 42
        bottomBarY = height - pad - bottomBarH

        bodyY = topBarY + topBarH + 4
        bodyH = (bottomBarY - bodyY - 4).coerceAtLeast(110)

        leftX = pad
        leftW = (width * 0.27f).roundToInt().coerceIn(124, 196)

        val inlineDrawer = knowledgeOpen && width >= 480
        knowledgeW = if (inlineDrawer) 148 else 0
        knowledgeX = width - pad - knowledgeW

        stageX = leftX + leftW + 4
        stageY = bodyY
        stageW = (width - stageX - pad - if (inlineDrawer) knowledgeW + 4 else 0).coerceAtLeast(140)
        stageH = bodyH

        innerX = stageX + 5
        innerY = stageY + 5
        innerW = stageW - 10
        innerH = stageH - 10

        timelineX = pad + 6
        timelineY = bottomBarY + 5
        timelineW = width - pad * 2 - 12
        timelineH = 10

        rebuildButtons()
    }

    private fun rebuildButtons() {
        buttons.clear()
        // 顶部栏按钮：[目录] 与 [关闭]
        val closeW = 20
        val idxW = 36
        val closeX = width - pad - 4 - closeW
        val idxX = closeX - 4 - idxW
        buttons.add(BtnBox(idxX, topBarY + 4, idxW, 18, text("hexponder.ui.index")) {
            PonderClientState.openIndex(this)
        })
        buttons.add(BtnBox(closeX, topBarY + 4, closeW, 18, "✕") {
            onClose()
        })

        // 底部传输与模式按钮（窄屏/4x缩放自动切换紧凑布局，确保左右不重叠）
        val compact = width < 380
        val by = bottomBarY + 19
        val bh = 18
        var bx = pad + 5

        fun addBtn(w: Int, label: String, active: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
            buttons.add(BtnBox(bx, by, w, bh, label, active, enabled, action))
            bx += w + (if (compact) 2 else 3)
        }

        val total = bundle.snapshots.size
        addBtn(if (compact) 20 else 24, "|<", enabled = total > 0) { restart() }
        addBtn(if (compact) 20 else 24, "<<", enabled = currentStepIndex > 0) { stepTo(currentStepIndex - 1, autoPlay = false) }
        addBtn(if (compact) 34 else 42, if (playing) text("hexponder.ui.pause") else text("hexponder.ui.play"), active = playing, enabled = total > 0) {
            togglePlay()
        }
        addBtn(if (compact) 20 else 24, ">>", enabled = currentStepIndex < total - 1) { stepTo(currentStepIndex + 1, autoPlay = false) }

        if (compact) {
            val speedLabel = if (speed == 1.0f) "1x" else if (speed == 2.0f) "2x" else "0.5x"
            addBtn(28, speedLabel, active = speed != 1.0f) {
                speed = when {
                    speed < 0.8f -> 1.0f
                    speed < 1.5f -> 2.0f
                    else -> 0.5f
                }
                rebuildButtons()
            }
        } else {
            bx += 4
            addBtn(26, "0.5x", active = speed == 0.5f) { speed = 0.5f; rebuildButtons() }
            addBtn(22, "1x", active = speed == 1.0f) { speed = 1.0f; rebuildButtons() }
            addBtn(22, "2x", active = speed == 2.0f) { speed = 2.0f; rebuildButtons() }
        }

        // 右对齐功能开关：[亲自一试] & [知识点]
        val kwW = if (compact) 42 else 54
        val sbW = if (compact) 48 else 62
        val rightEdge = width - pad - 5
        val kwX = rightEdge - kwW
        val sbX = kwX - (if (compact) 2 else 4) - sbW
        buttons.add(BtnBox(sbX, by, sbW, bh, text("hexponder.ui.sandbox"), active = sandboxMode, enabled = total > 0) {
            toggleSandbox()
        })
        buttons.add(BtnBox(kwX, by, kwW, bh, text("hexponder.ui.knowledge"), active = knowledgeOpen, enabled = bundle.knowledgeCards.isNotEmpty()) {
            knowledgeOpen = !knowledgeOpen
            computeLayout()
        })

        // 沙盒内清空按键
        if (sandboxMode) {
            buttons.add(BtnBox(innerX + innerW - 46, innerY + 4, 42, 16, text("hexponder.ui.clear")) {
                clearSandboxPatterns()
            })
        }
    }

    override fun tick() {
        super.tick()
        if (sandboxMode) {
            sandboxSpellcasting?.tick()
            return
        }
        val snap = currentSnapshot() ?: return

        val strokeTicks = snap.strokeTicks.coerceAtLeast(1)
        val totalStepTicks = strokeTicks + snap.holdTicks.coerceAtLeast(1)

        val prevProgress = (stepTicks / strokeTicks.toFloat()).coerceIn(0f, 1f)
        if (prevProgress < 1f || playing) {
            stepTicks += speed
        }
        val newProgress = (stepTicks / strokeTicks.toFloat()).coerceIn(0f, 1f)

        // 绘制进行中逐段触发笔顺音效
        if (snap.hasDisplayPattern && newProgress < 1f) {
            val pat = parsePattern(snap.displaySig, snap.displayStartDir)
            if (pat != null) {
                val segCount = max(1, pat.angleCount() + 1)
                val segIdx = floor(newProgress * segCount).toInt()
                if (lastSegmentSounded == -1) {
                    playUiSound(HexSounds.START_PATTERN)
                    lastSegmentSounded = 0
                } else if (segIdx > lastSegmentSounded) {
                    playUiSound(HexSounds.ADD_TO_PATTERN)
                    lastSegmentSounded = segIdx
                }
            }
        }

        // 绘制结束时刻（进入停留/展示计算后栈的状态）触发完成音效与可选暂停点
        if (!stepFinishedTriggered && newProgress >= 1f) {
            stepFinishedTriggered = true
            playCompletionSound(snap)
            if (snap.callout?.pause == true) {
                playing = false
                rebuildButtons()
            }
        }

        if (playing && stepTicks >= totalStepTicks) {
            if (currentStepIndex < bundle.snapshots.size - 1) {
                stepTo(currentStepIndex + 1, autoPlay = true)
            } else {
                playing = false
                stepTicks = totalStepTicks.toFloat()
                rebuildButtons()
            }
        }
    }

    private fun playCompletionSound(snap: StepSnapshot) {
        val sound = when {
            snap.stepType == "clear" -> HexSounds.STAFF_RESET
            snap.resolutionType == "ERRORED" -> HexSounds.CAST_FAILURE
            snap.resolutionType == "ESCAPED" || snap.stepType == "push" -> HexSounds.CAST_NORMAL
            snap.hasDisplayPattern -> HexSounds.CAST_SPELL
            else -> return
        }
        playUiSound(sound)
    }

    private fun playUiSound(event: net.minecraft.core.Holder<SoundEvent>) {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, 1.0f))
    }

    private fun clientRegs() = Minecraft.getInstance().connection?.registryAccess()

    private fun parsedImage(tag: CompoundTag): CastingImage =
        castingImageFromNbt(tag, clientRegs())

    private fun currentSnapshot(): StepSnapshot? =
        bundle.snapshots.getOrNull(currentStepIndex)

    private fun previousSnapshot(): StepSnapshot? =
        if (currentStepIndex > 0) bundle.snapshots.getOrNull(currentStepIndex - 1) else null

    /** 当前界面应展示的快照：若当前帧仍在描画笔顺，先展示上一步栈状态，笔顺画完瞬间刷新出结果栈。 */
    private fun effectiveStateSnapshot(): StepSnapshot? {
        val snap = currentSnapshot() ?: return null
        if (!snap.hasDisplayPattern) return snap
        val progress = (stepTicks / snap.strokeTicks.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        return if (progress < 0.999f) previousSnapshot() else snap
    }

    private fun togglePlay() {
        if (sandboxMode) exitSandbox()
        if (!playing && currentStepIndex >= bundle.snapshots.lastIndex &&
            stepTicks >= (currentSnapshot()?.let { it.strokeTicks + it.holdTicks } ?: 0)
        ) {
            restart()
            return
        }
        playing = !playing
        rebuildButtons()
    }

    private fun restart() {
        if (sandboxMode) exitSandbox()
        currentStepIndex = 0
        stepTicks = 0f
        lastSegmentSounded = -1
        stepFinishedTriggered = false
        playing = true
        stackScroll = 0
        rebuildButtons()
    }

    private fun stepTo(targetIdx: Int, autoPlay: Boolean) {
        if (sandboxMode) exitSandbox()
        if (bundle.snapshots.isEmpty()) return
        currentStepIndex = targetIdx.coerceIn(0, bundle.snapshots.lastIndex)
        stepTicks = 0f
        lastSegmentSounded = -1
        stepFinishedTriggered = false
        playing = autoPlay
        stackScroll = 0
        rebuildButtons()
    }

    private fun toggleSandbox() {
        if (sandboxMode) {
            exitSandbox()
        } else {
            enterSandbox()
        }
    }

    private fun enterSandbox() {
        sandboxMode = true
        playing = false
        val stateSnap = effectiveStateSnapshot()
        sandboxImageNbt = stateSnap?.imageNbt?.copy() ?: CompoundTag()
        sandboxStackTags = stateSnap?.stackTags?.toList() ?: emptyList()
        sandboxRavenmindTag = stateSnap?.ravenmindTag?.copy()
        sandboxOpenParens = stateSnap?.openParens ?: 0
        sandboxParenthesizedTags = stateSnap?.parenthesizedTags?.toList()
            ?: extractParenthesizedTags(sandboxImageNbt)
        sandboxEscapeNext = stateSnap?.escapeNext ?: false
        sandboxConsoleLines = emptyList()
        sandboxMishap = null
        sandboxColorMap.clear()
        setupSandboxWidget()
        rebuildButtons()
    }

    private fun extractParenthesizedTags(imageNbt: CompoundTag): List<CompoundTag> {
        val image = parsedImage(imageNbt)
        return image.parenthesized.map { iotaToNbt(it.iota, clientRegs()) }.toList()
    }

    private fun exitSandbox() {
        sandboxMode = false
        sandboxSpellcasting = null
        Minecraft.getInstance().soundManager.stop(HexSounds.CASTING_AMBIANCE.value().location, null)
        rebuildButtons()
    }

    private fun setupSandboxWidget() {
        val mc = Minecraft.getInstance()
        val sc = GuiSpellcasting(InteractionHand.MAIN_HAND, ArrayList(), emptyList(), null, 0)
        sc.init(mc, width, height)
        mc.soundManager.stop(HexSounds.CASTING_AMBIANCE.value().location, null)

        val access = sc as PonderSpellcastingAccess
        access.`setSandboxMode$abw`(true)
        access.`setHexSizeOverride$abw`(bundle.hexSize.coerceIn(14f, 24f))
        access.`setCoordsOffsetOverride$abw`(Vec2(innerX + innerW / 2f, innerY + innerH / 2f + 4f))
        access.`setOnDrawPattern$abw` { pattern ->
            sandboxSessionSeq++
            val seq = sandboxSessionSeq
            MsgPonderSandboxExecC2S(seq, pattern.anglesSignature(), pattern.startDir.name, sandboxImageNbt).sendToServer()
        }
        sandboxSpellcasting = sc
    }

    private fun clearSandboxPatterns() {
        val sc = sandboxSpellcasting ?: return
        (sc as PonderSpellcastingAccess).`clearPatterns$abw`()
        sandboxColorMap.clear()
        playUiSound(HexSounds.STAFF_RESET)
    }

    fun onSandboxResult(msg: MsgPonderSandboxExecS2C) {
        if (!sandboxMode) return
        sandboxImageNbt = msg.imageNbt
        val parsed = parsedImage(msg.imageNbt)
        sandboxStackTags = parsed.stack.map { iotaToNbt(it, clientRegs()) }.toList()
        sandboxRavenmindTag = parsed.getComponent(at.petrak.hexcasting.common.lib.hex.HexImageComponents.RAVENMIND.get())
            ?.let { iotaToNbt(it, clientRegs()) }
        sandboxOpenParens = parsed.parenCount
        sandboxParenthesizedTags = parsed.parenthesized.map { iotaToNbt(it.iota, clientRegs()) }.toList()
        sandboxEscapeNext = parsed.escapeNext
        sandboxConsoleLines = msg.consoleLines
        sandboxMishap = msg.mishapText

        val sc = sandboxSpellcasting
        if (sc != null) {
            val access = sc as PonderSpellcastingAccess
            val type = runCatching { ResolvedPatternType.valueOf(msg.resolutionType) }
                .getOrDefault(ResolvedPatternType.EVALUATED)
            access.`setLastPatternType$abw`(type)
        }
        when (msg.resolutionType) {
            "ERRORED" -> playUiSound(HexSounds.CAST_FAILURE)
            "ESCAPED" -> playUiSound(HexSounds.CAST_NORMAL)
            else -> playUiSound(HexSounds.CAST_SPELL)
        }
    }

    // ==================== 渲染管线 ====================

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 原版深暗半透明遮罩，保证在游戏背景前聚焦
        graphics.fill(0, 0, width, height, 0xCC09070F.toInt())

        renderTopBar(graphics)
        renderLeftPanel(graphics, mouseX, mouseY)
        renderStagePanel(graphics, mouseX, mouseY, partialTick)
        if (knowledgeOpen && knowledgeW > 0) {
            renderKnowledgeDrawer(graphics, knowledgeX, bodyY, knowledgeW, bodyH)
        }
        renderBottomBar(graphics, mouseX, mouseY)

        // 绘制按钮与浮动提示气泡（在舞台裁剪之外的顶层）
        for (btn in buttons) {
            PonderGuiDraw.drawPixelButton(
                g = graphics,
                font = font,
                x = btn.x,
                y = btn.y,
                w = btn.w,
                h = btn.h,
                label = btn.label,
                hovered = btn.contains(mouseX.toDouble(), mouseY.toDouble()),
                active = btn.active,
                enabled = btn.enabled,
            )
        }

        // 演示提示文本（Z = 310，低于弹出式札记弹窗 Z = 360）
        if (!sandboxMode) {
            renderCalloutIfPresent(graphics)
        }
        if (knowledgeOpen && knowledgeW == 0) {
            // 小屏 / GUI Scale 4x 弹出式知识点浮层（最高内容图层 Z = 360，盖过提示文本与引导线）
            val (ox, oy, ow, oh) = popupBounds()
            graphics.flush()
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate(0f, 0f, 360f)
            RenderSystem.disableDepthTest()
            try {
                graphics.fill(pad, bodyY, width - pad, bodyY + bodyH, 0x8808060D.toInt())
                renderKnowledgeDrawer(graphics, ox, oy, ow, oh, showClose = true)
                graphics.flush()
            } finally {
                RenderSystem.enableDepthTest()
                pose.popPose()
            }
        }
        if (!(knowledgeOpen && knowledgeW == 0)) {
            hoveredStackTag?.let { tag ->
                graphics.renderTooltip(font, (iotaFromNbt(tag, clientRegs())?.display() ?: net.minecraft.network.chat.Component.literal("<?>")), mouseX, mouseY)
            }
        }
        renderTimelineTooltip(graphics, mouseX, mouseY)
    }

    private fun renderTopBar(g: GuiGraphics) {
        PonderGuiDraw.drawBeveledPanel(g, pad, topBarY, width - pad * 2, topBarH)

        val icon = PonderGuiDraw.resolveItemStack(bundle.iconItemId)
        if (!icon.isEmpty) {
            g.renderItem(icon, pad + 5, topBarY + 5)
        }
        val titleX = pad + if (icon.isEmpty) 7 else 25
        val titleStr = localize(bundle.title)
        g.drawString(font, titleStr, titleX, topBarY + 4, PonderGuiDraw.TEXT_MAIN, true)

        val snap = currentSnapshot()
        val subLine = if (sandboxMode) {
            text("hexponder.ui.sandbox_badge")
        } else if (snap != null && snap.title.isNotEmpty()) {
            localize(snap.title)
        } else {
            localize(bundle.subtitle)
        }
        val maxSubW = (width - titleX - 165).coerceAtLeast(40)
        val trimmedSub = font.plainSubstrByWidth(subLine, maxSubW)
        g.drawString(font, trimmedSub, titleX, topBarY + 14, PonderGuiDraw.TEXT_MUTED, false)

        // 右侧步骤与模式徽章
        val stepStr = if (sandboxMode) {
            text("hexponder.ui.sandbox_badge")
        } else if (bundle.snapshots.isNotEmpty()) {
            "${currentStepIndex + 1}/${bundle.snapshots.size}"
        } else {
            "0/0"
        }
        val badgeColor = if (sandboxMode) PonderGuiDraw.SANDBOX_ACCENT else PonderGuiDraw.TEXT_CYAN
        val badgeX = width - pad - 68 - font.width(stepStr) - 8
        g.drawString(font, stepStr, badgeX, topBarY + 9, badgeColor, true)
    }

    private fun renderLeftPanel(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        hoveredStackTag = null
        PonderGuiDraw.drawBeveledPanel(g, leftX, bodyY, leftW, bodyH)

        val snap = if (sandboxMode) null else effectiveStateSnapshot()
        val stackTags = if (sandboxMode) sandboxStackTags else (snap?.stackTags ?: emptyList())
        val openParens = if (sandboxMode) sandboxOpenParens else (snap?.openParens ?: 0)
        val escapeNext = if (sandboxMode) sandboxEscapeNext else (snap?.escapeNext ?: false)
        val ravenTag = if (sandboxMode) sandboxRavenmindTag else snap?.ravenmindTag
        val consoleLines = if (sandboxMode) sandboxConsoleLines else (snap?.consoleLines ?: emptyList())
        val mishapText = if (sandboxMode) sandboxMishap else snap?.mishapText
        val poppedCount = if (sandboxMode) 0 else (snap?.poppedCount ?: 0)
        val pushedCount = if (sandboxMode) 0 else (snap?.pushedCount ?: 0)

        // 标题行：施法栈
        g.drawString(font, text("hexponder.ui.stack_header"), leftX + 6, bodyY + 5, PonderGuiDraw.AMETHYST_BRIGHT, true)
        val countLabel = "${stackTags.size}"
        g.drawString(font, countLabel, leftX + leftW - 6 - font.width(countLabel), bodyY + 5, PonderGuiDraw.TEXT_MUTED, false)

        // 下半部分预留给 CastingImage 状态条 + 输出台
        val consoleH = 38
        val imageBoxH = 28
        val stackBoxY = bodyY + 16
        val stackBoxH = (bodyH - 16 - imageBoxH - consoleH - 14).coerceAtLeast(48)

        PonderGuiDraw.drawRecessedBox(g, leftX + 5, stackBoxY, leftW - 10, stackBoxH)

        // 栈列表（从栈顶向下展示，贴近学习者直觉）
        val rowH = 15
        val maxRows = ((stackBoxH - 4) / rowH).coerceAtLeast(1)
        val maxScroll = (stackTags.size - maxRows).coerceAtLeast(0)
        stackScroll = stackScroll.coerceIn(0, maxScroll)

        g.enableScissor(leftX + 6, stackBoxY + 1, leftX + leftW - 6, stackBoxY + stackBoxH - 1)
        if (stackTags.isEmpty()) {
            val emptyTxt = text("hexponder.ui.empty_stack")
            val ew = font.width(emptyTxt)
            g.drawString(font, emptyTxt, leftX + (leftW - ew) / 2, stackBoxY + stackBoxH / 2 - 4, 0xFF6B6280.toInt(), false)
        } else {
            val topFirstIndices = (stackTags.lastIndex downTo 0).toList()
            for (visIdx in 0 until min(maxRows, topFirstIndices.size - stackScroll)) {
                val realIdx = topFirstIndices[visIdx + stackScroll]
                val tag = stackTags[realIdx]
                val rowY = stackBoxY + 2 + visIdx * rowH
                val rowX = leftX + 7
                val rowW = leftW - 14
                if (mouseX >= rowX && mouseX < rowX + rowW && mouseY >= rowY && mouseY < rowY + rowH - 1) {
                    hoveredStackTag = tag
                }

                val isNewlyPushed = !sandboxMode && pushedCount > 0 && realIdx >= stackTags.size - pushedCount
                val iotaCol = 0xFF000000.toInt() or ((iotaFromNbt(tag, clientRegs())?.type?.color() ?: 0) and 0xFFFFFF)

                PonderGuiDraw.drawRecessedBox(
                    g, rowX, rowY, rowW, rowH - 1,
                    bg = if (isNewlyPushed) 0xFF1F1930.toInt() else 0xFF130F1D.toInt(),
                )
                // 类型色条
                g.fill(rowX + 1, rowY + 1, rowX + 3, rowY + rowH - 2, iotaCol)

                val stackIndexFromTop = stackTags.lastIndex - realIdx
                val prefix = if (stackIndexFromTop == 0) "▲" else "#$stackIndexFromTop"
                g.drawString(font, prefix, rowX + 5, rowY + 3, PonderGuiDraw.TEXT_MUTED, false)

                val prefixW = font.width(prefix) + 8
                val badgeTxt = if (isNewlyPushed) "+" else ""
                val badgeW = if (badgeTxt.isNotEmpty()) font.width(badgeTxt) + 4 else 0
                val availW = (rowW - prefixW - badgeW - 6).coerceAtLeast(24)

                // Tag-Only 渲染：绝不在客户端反序列化实体/世界 iota
                val seq: FormattedCharSequence = (iotaFromNbt(tag, clientRegs())?.displayWithMaxWidth(availW, font) ?: net.minecraft.network.chat.Component.empty().visualOrderText)
                g.drawString(font, seq, rowX + 5 + prefixW, rowY + 3, PonderGuiDraw.TEXT_MAIN, false)
                if (badgeTxt.isNotEmpty()) {
                    g.drawString(font, badgeTxt, rowX + rowW - badgeW, rowY + 3, 0xFF6BCB77.toInt(), true)
                }
            }
        }
        g.disableScissor()

        // 弹出提示条（如果有消费）
        if (poppedCount > 0 && !sandboxMode) {
            val popTxt = I18n.get("hexponder.ui.popped_hint", poppedCount)
            g.drawString(font, font.plainSubstrByWidth(popTxt, leftW - 14), leftX + 6, stackBoxY + stackBoxH + 2, PonderGuiDraw.TEXT_ERR, false)
        }

        // CastingImage 映像状态区
        val imgY = stackBoxY + stackBoxH + 12
        PonderGuiDraw.drawRecessedBox(g, leftX + 5, imgY, leftW - 10, imageBoxH)
        val parenCol = if (openParens > 0) PonderGuiDraw.TEXT_GOLD else PonderGuiDraw.TEXT_MUTED
        val escCol = if (escapeNext) PonderGuiDraw.TEXT_GOLD else PonderGuiDraw.TEXT_MUTED
        g.drawString(font, "${text("hexponder.ui.parens")}:$openParens", leftX + 9, imgY + 4, parenCol, false)
        val escLabel = "${text("hexponder.ui.escape")}:${if (escapeNext) text("hexponder.ui.on") else text("hexponder.ui.off")}"
        g.drawString(font, escLabel, leftX + leftW - 9 - font.width(escLabel), imgY + 4, escCol, false)

        val ravenPrefix = "${text("hexponder.ui.raven")}: "
        g.drawString(font, ravenPrefix, leftX + 9, imgY + 17, PonderGuiDraw.TEXT_MUTED, false)
        val rw = font.width(ravenPrefix)
        val ravenAvail = (leftW - 18 - rw).coerceAtLeast(24)
        if (ravenTag != null && !ravenTag.isEmpty) {
            val rSeq = (iotaFromNbt(ravenTag, clientRegs())?.displayWithMaxWidth(ravenAvail, font) ?: net.minecraft.network.chat.Component.empty().visualOrderText)
            g.drawString(font, rSeq, leftX + 9 + rw, imgY + 17, PonderGuiDraw.TEXT_BODY, false)
        } else {
            g.drawString(font, "Null", leftX + 9 + rw, imgY + 17, 0xFF6B6280.toInt(), false)
        }

        // 输出/事故台
        val conY = imgY + imageBoxH + 4
        PonderGuiDraw.drawRecessedBox(
            g, leftX + 5, conY, leftW - 10, consoleH,
            borderHighlight = if (mishapText != null) PonderGuiDraw.TEXT_ERR else 0,
        )
        g.enableScissor(leftX + 7, conY + 2, leftX + leftW - 7, conY + consoleH - 2)
        val displayMsg = mishapText ?: consoleLines.lastOrNull()
        if (displayMsg != null) {
            val wrapped = font.split(displayMsg, leftW - 16)
            var cy = conY + 4
            val col = if (mishapText != null) PonderGuiDraw.TEXT_ERR else PonderGuiDraw.TEXT_BODY
            for (line in wrapped.take(3)) {
                g.drawString(font, line, leftX + 8, cy, col, false)
                cy += font.lineHeight + 1
            }
        }
        g.disableScissor()
    }

    private fun renderStagePanel(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 明确区分带有石板边框与紫晶角钉的施法演示区
        PonderGuiDraw.drawCastingStageFrame(g, stageX, stageY, stageW, stageH, sandboxMode)

        g.enableScissor(innerX, innerY, innerX + innerW, innerY + innerH)
        if (sandboxMode) {
            renderSandboxStage(g, mouseX, mouseY, partialTick)
        } else {
            renderPlaybackStage(g, partialTick)
        }
        renderParenthesizedTray(g, mouseX, mouseY)
        g.disableScissor()

        // 舞台顶部动作/章节小标牌（仅演示时显示精简名称）
        val snap = currentSnapshot()
        val labelTitle = if (!sandboxMode && snap != null) {
            if (snap.title.isNotEmpty()) localize(snap.title) else snap.stepType
        } else {
            ""
        }
        if (labelTitle.isNotEmpty()) {
            val maxW = (innerW - 16).coerceAtLeast(30)
            val clipped = font.plainSubstrByWidth(labelTitle, maxW)
            val boxW = font.width(clipped) + 10
            PonderGuiDraw.drawRecessedBox(g, innerX + 4, innerY + 4, boxW, 16, 0xCC120E1D.toInt())
            g.drawString(
                font,
                clipped,
                innerX + 9,
                innerY + 8,
                PonderGuiDraw.TEXT_MAIN,
                true,
            )
        }
    }

    private fun renderSandboxStage(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val sc = sandboxSpellcasting ?: return
        val hexSize = bundle.hexSize.coerceIn(14f, 24f)
        val offset = Vec2(innerX + innerW / 2f, innerY + innerH / 2f + 4f)
        renderLatticeDots(g, hexSize, offset)

        PonderPatternColorState.begin(sandboxColorMap)
        try {
            sc.render(g, mouseX, mouseY, partialTick)
        } finally {
            PonderPatternColorState.end()
        }
    }

    private fun renderParenthesizedTray(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val stateSnap = effectiveStateSnapshot()
        val openParens = if (sandboxMode) sandboxOpenParens else (stateSnap?.openParens ?: 0)
        val parenTags = if (sandboxMode) sandboxParenthesizedTags else (stateSnap?.parenthesizedTags ?: emptyList())
        if (openParens <= 0 && parenTags.isEmpty()) return

        val boxW = (innerW * 0.44f).roundToInt().coerceIn(96, 172)
        val maxShow = 4
        val hiddenCount = (parenTags.size - maxShow).coerceAtLeast(0)
        val visibleTags = if (parenTags.size > maxShow) parenTags.takeLast(maxShow) else parenTags
        val rowCount = max(1, visibleTags.size) + if (hiddenCount > 0) 1 else 0
        val rowH = 12
        val headerH = 14
        val boxH = headerH + rowCount * rowH + 4
        val boxX = innerX + 4
        val boxY = (innerY + innerH - boxH - 4).coerceAtLeast(innerY + 24)

        val pose = g.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 260f)
        try {
            PonderGuiDraw.drawRecessedBox(
                g = g,
                x = boxX,
                y = boxY,
                w = boxW,
                h = boxH,
                bg = 0xD8110D1B.toInt(),
                borderHighlight = 0xFFDDCC73.toInt(),
            )

            val headerTxt = font.plainSubstrByWidth(
                "${text("hexponder.ui.paren_buffer")} [×$openParens] (${parenTags.size})",
                boxW - 10,
            )
            g.drawString(font, headerTxt, boxX + 5, boxY + 4, 0xFFDDCC73.toInt(), true)

            var ry = boxY + headerH + 1
            if (parenTags.isEmpty()) {
                g.drawString(font, "...", boxX + 6, ry + 1, PonderGuiDraw.TEXT_MUTED, false)
            } else {
                if (hiddenCount > 0) {
                    g.drawString(font, "+$hiddenCount ...", boxX + 6, ry + 1, PonderGuiDraw.TEXT_MUTED, false)
                    ry += rowH
                }
                for (tag in visibleTags) {
                    val iotaCol = 0xFF000000.toInt() or ((iotaFromNbt(tag, clientRegs())?.type?.color() ?: 0) and 0xFFFFFF)
                    g.fill(boxX + 4, ry + 1, boxX + 6, ry + rowH - 2, iotaCol)
                    val seq = (iotaFromNbt(tag, clientRegs())?.displayWithMaxWidth(boxW - 14, font) ?: net.minecraft.network.chat.Component.empty().visualOrderText)
                    g.drawString(font, seq, boxX + 9, ry + 1, PonderGuiDraw.TEXT_MAIN, false)
                    if (mouseX >= boxX + 3 && mouseX < boxX + boxW - 3 && mouseY >= ry && mouseY < ry + rowH) {
                        hoveredStackTag = tag
                    }
                    ry += rowH
                }
            }
        } finally {
            pose.popPose()
        }
    }

    private fun resolveNativePatternType(snap: StepSnapshot): ResolvedPatternType = when (snap.resolutionType) {
        "EVALUATED", "PEEK" -> ResolvedPatternType.EVALUATED
        "ESCAPED", "PUSH" -> ResolvedPatternType.ESCAPED
        "ERRORED" -> ResolvedPatternType.ERRORED
        "INVALID" -> ResolvedPatternType.INVALID
        "UNDONE" -> ResolvedPatternType.UNDONE
        else -> ResolvedPatternType.UNRESOLVED
    }

    private fun renderPlaybackStage(g: GuiGraphics, partialTick: Float) {
        val snap = currentSnapshot() ?: return
        val visible = collectVisiblePatterns(currentStepIndex)

        // 自动计算舞台适配的 hexSize 与中心偏移，确保任意复杂图案绝不裁切
        val (fitHexSize, centerOffset) = computeStageTransform(visible)
        renderLatticeDots(g, fitHexSize, centerOffset)

        val layouts = visible.map { (idx, s, pat) ->
            val originPx = coordToPx(HexCoord(s.originQ, s.originR), fitHexSize, centerOffset)
            val points = pat.toLines(fitHexSize, originPx)
            val dups = findDupIndices(pat.positions())
            StagePatternLayout(idx, s, pat, points, dups)
        }

        val colorMap = HashMap<Int, Pair<Int, Int>>()
        for (l in layouts) {
            if (l.snap.patternColor != -1) {
                colorMap[l.snapIndex] = PonderPatternColorState.tailHeadFromColor(l.snap.patternColor)
            }
        }

        val displayTicks = stepTicks + if (playing || stepTicks < snap.strokeTicks) partialTick * speed else 0f
        val drawProgress = (displayTicks / snap.strokeTicks.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

        val snapNative = resolveNativePatternType(snap)
        var cursorPos: Vec2? = null
        var cursorColor: Int = if (snap.patternColor != -1) snap.patternColor else (snapNative.color or 0xFF000000.toInt())

        val oldShader = RenderSystem.getShader()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        PonderPatternColorState.begin(colorMap)
        try {
            val mat = g.pose().last().pose()
            for (layout in layouts) {
                val isCurrent = layout.snapIndex == currentStepIndex
                val pts = layout.points
                if (pts.isEmpty()) continue

                val nativeType = resolveNativePatternType(layout.snap)
                val isDrawing = isCurrent && drawProgress < 1f
                val baseTail = when {
                    isDrawing -> 0xFF64C8FF.toInt()
                    layout.snap.patternColor != -1 -> PonderPatternColorState.tailHeadFromColor(layout.snap.patternColor).first
                    else -> nativeType.color or 0xC8000000.toInt()
                }
                val baseHead = when {
                    isDrawing -> 0xFFFECBE6.toInt()
                    layout.snap.patternColor != -1 -> PonderPatternColorState.tailHeadFromColor(layout.snap.patternColor).second
                    else -> nativeType.fadeColor or 0xC8000000.toInt()
                }
                val baseFlow = when {
                    isDrawing -> 0.1f
                    nativeType.success -> 0.2f
                    else -> 0.9f
                }
                val drawLast = !isDrawing
                val eff = DrawPatternHook.effect(
                    drawLast,
                    baseTail,
                    baseHead,
                    baseFlow,
                    layout.snapIndex.toDouble(),
                )

                val spotArgb = eff.tail
                val r = ((spotArgb ushr 16) and 0xFF) / 255f
                val gr = ((spotArgb ushr 8) and 0xFF) / 255f
                val b = (spotArgb and 0xFF) / 255f
                drawSpot(mat, pts[0], 2.2f, r, gr, b, 0.9f)

                val ptsToDraw: List<Vec2>
                if (isDrawing) {
                    ptsToDraw = slicePointsProgress(pts, drawProgress)
                    cursorPos = ptsToDraw.lastOrNull() ?: pts[0]
                    cursorColor = eff.tail or 0xFF000000.toInt()
                } else {
                    ptsToDraw = pts
                    if (isCurrent) {
                        cursorPos = pts.last()
                        cursorColor = eff.tail or 0xFF000000.toInt()
                    }
                }

                if (ptsToDraw.size >= 2) {
                    drawPatternFromPoints(
                        mat,
                        ptsToDraw,
                        layout.dupIndices,
                        eff.drawLast,
                        eff.tail,
                        eff.head,
                        eff.flowIrregular,
                        0.2f,
                        1.0f,
                        layout.snapIndex.toDouble(),
                    )
                }
            }
        } finally {
            PonderPatternColorState.end()
            RenderSystem.enableDepthTest()
            if (oldShader != null) {
                RenderSystem.setShader { oldShader }
            }
        }

        // 在笔尖渲染可配置的 Minecraft 物品贴图光标（默认法杖）
        if (cursorPos != null && snap.cursorItemId.isNotEmpty() && !snap.cursorItemId.equals("none", ignoreCase = true)) {
            val cursorStack = PonderGuiDraw.resolveItemStack(snap.cursorItemId)
            PonderGuiDraw.renderItemCursor(g, cursorStack, cursorPos.x, cursorPos.y, cursorColor)
        } else if (cursorPos != null) {
            PonderGuiDraw.renderItemCursor(g, net.minecraft.world.item.ItemStack.EMPTY, cursorPos.x, cursorPos.y, cursorColor)
        }
    }

    private fun renderLatticeDots(g: GuiGraphics, hexSize: Float, offset: Vec2) {
        val c0 = pxToCoord(Vec2(innerX.toFloat(), innerY.toFloat()), hexSize, offset)
        val c1 = pxToCoord(Vec2((innerX + innerW).toFloat(), (innerY + innerH).toFloat()), hexSize, offset)
        val q0 = min(c0.q, c1.q) - 1
        val q1 = max(c0.q, c1.q) + 1
        val r0 = min(c0.r, c1.r) - 1
        val r1 = max(c0.r, c1.r) + 1
        for (q in q0..q1) {
            for (r in r0..r1) {
                val p = coordToPx(HexCoord(q, r), hexSize, offset)
                val ix = p.x.roundToInt()
                val iy = p.y.roundToInt()
                if (ix >= innerX + 3 && ix < innerX + innerW - 3 && iy >= innerY + 3 && iy < innerY + innerH - 3) {
                    g.fill(ix - 1, iy - 1, ix + 1, iy + 1, 0x389D8EC4)
                }
            }
        }
    }

    private fun collectVisiblePatterns(upToIndex: Int): List<Triple<Int, StepSnapshot, HexPattern>> {
        if (bundle.snapshots.isEmpty() || upToIndex < 0) return emptyList()
        var startIdx = upToIndex
        while (startIdx > 0 && !bundle.snapshots[startIdx].clearCanvasBefore && bundle.snapshots[startIdx].stepType != "clear") {
            startIdx--
        }
        val list = ArrayList<Triple<Int, StepSnapshot, HexPattern>>()
        for (i in startIdx..upToIndex) {
            val s = bundle.snapshots[i]
            if (!s.hasDisplayPattern) continue
            val pat = parsePattern(s.displaySig, s.displayStartDir) ?: continue
            list.add(Triple(i, s, pat))
        }
        return list
    }

    private fun computeStageTransform(visible: List<Triple<Int, StepSnapshot, HexPattern>>): Pair<Float, Vec2> {
        val baseHex = bundle.hexSize.coerceIn(12f, 34f)
        if (visible.isEmpty()) {
            return baseHex to Vec2(innerX + innerW / 2f, innerY + innerH / 2f + 4f)
        }
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for ((_, s, pat) in visible) {
            val originPx = coordToPx(HexCoord(s.originQ, s.originR), baseHex, Vec2.ZERO)
            for (pt in pat.toLines(baseHex, originPx)) {
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.y > maxY) maxY = pt.y
            }
        }

        val rawW = (maxX - minX).coerceAtLeast(1f)
        val rawH = (maxY - minY).coerceAtLeast(1f)
        val availW = (innerW - 44).coerceAtLeast(60).toFloat()
        val availH = (innerH - 56).coerceAtLeast(60).toFloat()
        val scale = min(1.0f, min(availW / rawW, availH / rawH)).coerceAtLeast(0.45f)
        val fitHex = baseHex * scale

        val cx = ((minX + maxX) * 0.5f) * scale
        val cy = ((minY + maxY) * 0.5f) * scale
        val stageCenterX = innerX + innerW * 0.5f
        val stageCenterY = innerY + innerH * 0.5f + 8f
        return fitHex to Vec2(stageCenterX - cx, stageCenterY - cy)
    }

    private fun slicePointsProgress(full: List<Vec2>, progress: Float): List<Vec2> {
        if (full.size <= 1) return full
        val segs = full.size - 1
        val exact = progress.coerceIn(0f, 1f) * segs
        val fullSegs = floor(exact).toInt().coerceIn(0, segs)
        val frac = exact - fullSegs
        val out = ArrayList<Vec2>(fullSegs + 2)
        for (i in 0..fullSegs) {
            out.add(full[i])
        }
        if (fullSegs < segs && frac > 0.005f) {
            val a = full[fullSegs]
            val b = full[fullSegs + 1]
            out.add(Vec2(Mth.lerp(frac, a.x, b.x), Mth.lerp(frac, a.y, b.y)))
        }
        return out
    }

    private fun renderCalloutIfPresent(g: GuiGraphics) {
        val snap = currentSnapshot() ?: return
        val callout = snap.callout ?: return
        val txt = localize(callout.text)
        if (txt.isEmpty()) return

        val boxW = (stageW * 0.56f).roundToInt().coerceIn(135, 220)
        val lines = font.split(Component.literal(txt), boxW - 14)
        val iotaDisplays = callout.inlineIotaTags.map { tag ->
            val col = 0xFF000000.toInt() or ((iotaFromNbt(tag, clientRegs())?.type?.color() ?: 0) and 0xFFFFFF)
            (iotaFromNbt(tag, clientRegs())?.displayWithMaxWidth(boxW - 22, font) ?: net.minecraft.network.chat.Component.empty().visualOrderText) to col
        }
        val boxH = 10 + lines.size * (font.lineHeight + 2) +
            if (iotaDisplays.isEmpty()) 0 else 6 + iotaDisplays.size * (font.lineHeight + 3)

        val boxX = (innerX + innerW - boxW - 8).coerceAtLeast(innerX + 4)
        val boxY = (innerY + innerH - boxH - 8).coerceAtLeast(innerY + 24)

        var targetX = innerX + innerW / 2
        var targetY = innerY + innerH / 2
        when (callout.anchor) {
            "stack" -> {
                targetX = leftX + leftW - 8
                targetY = (bodyY + 24 + callout.anchorIndex.coerceIn(0, 6) * 15).coerceAtMost(bodyY + bodyH - 60)
            }
            "hud" -> {
                targetX = leftX + leftW - 10
                targetY = bodyY + bodyH - 58
            }
            "coord" -> {
                val visible = collectVisiblePatterns(currentStepIndex)
                val (fitHex, off) = computeStageTransform(visible)
                val q = callout.anchorQ ?: snap.originQ
                val r = callout.anchorR ?: snap.originR
                val px = coordToPx(HexCoord(q, r), fitHex, off)
                targetX = px.x.roundToInt().coerceIn(innerX + 6, innerX + innerW - 6)
                targetY = px.y.roundToInt().coerceIn(innerY + 6, innerY + innerH - 6)
            }
            else -> {
                targetX = innerX + (innerW * 0.42f).roundToInt()
                targetY = innerY + (innerH * 0.45f).roundToInt()
            }
        }

        PonderGuiDraw.drawCalloutOverlay(
            g = g,
            font = font,
            boxX = boxX,
            boxY = boxY,
            boxW = boxW,
            lines = lines,
            iotaDisplays = iotaDisplays,
            targetX = targetX,
            targetY = targetY,
            drawLeader = true,
            accentColor = callout.accentColor,
        )
    }

    private fun popupBounds(): IntArray {
        val ow = (width - 28).coerceAtMost(280)
        val oh = (bodyH - 12).coerceAtMost(210)
        val ox = (width - ow) / 2
        val oy = bodyY + (bodyH - oh) / 2
        return intArrayOf(ox, oy, ow, oh)
    }

    private fun renderKnowledgeDrawer(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, showClose: Boolean = false) {
        PonderGuiDraw.drawBeveledPanel(g, x, y, w, h)
        g.drawString(font, text("hexponder.ui.knowledge_header"), x + 6, y + 6, PonderGuiDraw.TEXT_GOLD, true)
        if (showClose) {
            g.drawString(font, "✕", x + w - 14, y + 6, PonderGuiDraw.TEXT_MUTED, true)
        }

        val contentY = y + 18
        val contentH = (h - 23).coerceAtLeast(24)
        PonderGuiDraw.drawRecessedBox(g, x + 5, contentY, w - 10, contentH)

        g.enableScissor(x + 6, contentY + 2, x + w - 6, contentY + contentH - 2)
        var cy = contentY + 5 - knowledgeScroll
        for (card in bundle.knowledgeCards) {
            val titleLines = font.split(Component.literal(localize(card.title)), w - 18)
            for (tl in titleLines) {
                g.drawString(font, tl, x + 8, cy, PonderGuiDraw.AMETHYST_BRIGHT, true)
                cy += font.lineHeight + 1
            }
            val bodyLines = font.split(Component.literal(localize(card.body)), w - 18)
            for (bl in bodyLines) {
                g.drawString(font, bl, x + 8, cy, PonderGuiDraw.TEXT_BODY, false)
                cy += font.lineHeight + 1
            }
            for (tag in card.inlineIotaTags) {
                val col = 0xFF000000.toInt() or ((iotaFromNbt(tag, clientRegs())?.type?.color() ?: 0) and 0xFFFFFF)
                val seq = (iotaFromNbt(tag, clientRegs())?.displayWithMaxWidth(w - 24, font) ?: net.minecraft.network.chat.Component.empty().visualOrderText)
                PonderGuiDraw.drawRecessedBox(g, x + 8, cy, w - 16, font.lineHeight + 3, 0xFF0C0914.toInt(), col)
                g.drawString(font, seq, x + 11, cy + 2, col, false)
                cy += font.lineHeight + 5
            }
            cy += 6
        }
        g.disableScissor()
    }

    private fun renderBottomBar(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        PonderGuiDraw.drawBeveledPanel(g, pad, bottomBarY, width - pad * 2, bottomBarH)
        PonderGuiDraw.drawRecessedBox(g, timelineX, timelineY, timelineW, timelineH)

        val total = bundle.snapshots.size
        if (total <= 0) return

        val fillRatio = (currentStepIndex + 1).toFloat() / total.toFloat()
        val fillW = (timelineW * fillRatio).roundToInt().coerceIn(2, timelineW - 2)
        g.fill(timelineX + 1, timelineY + 1, timelineX + fillW, timelineY + timelineH - 1, PonderGuiDraw.AMETHYST_DARK)
        g.fill(timelineX + 1, timelineY + 1, timelineX + fillW, timelineY + 3, PonderGuiDraw.AMETHYST_BRIGHT)

        // 方形关键帧标记（点击可 O(1) 跳转）
        for (i in 0 until total) {
            val snap = bundle.snapshots[i]
            val mx = timelineX + (((i + 0.5f) / total.toFloat()) * timelineW).roundToInt()
            val my = timelineY + timelineH / 2
            val isChapter = snap.chapter.isNotEmpty() &&
                (i == 0 || bundle.snapshots[i - 1].chapter != snap.chapter)
            val isActive = i == currentStepIndex
            val half = when {
                isActive -> 4
                isChapter -> 3
                else -> 2
            }
            val col = when {
                isActive -> PonderGuiDraw.TEXT_MAIN
                snap.resolutionType == "ERRORED" -> PonderGuiDraw.TEXT_ERR
                isChapter -> PonderGuiDraw.TEXT_GOLD
                else -> 0xFFB8AAD6.toInt()
            }
            g.fill(mx - half - 1, my - half - 1, mx + half + 1, my + half + 1, PonderGuiDraw.OUTLINE)
            g.fill(mx - half, my - half, mx + half, my + half, col)
        }
    }

    private fun renderTimelineTooltip(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val total = bundle.snapshots.size
        if (total <= 0) return
        if (mouseX < timelineX || mouseX > timelineX + timelineW || mouseY < timelineY - 3 || mouseY > timelineY + timelineH + 3) {
            return
        }
        val ratio = ((mouseX - timelineX).toFloat() / timelineW.toFloat()).coerceIn(0f, 0.999f)
        val idx = (ratio * total).toInt().coerceIn(0, total - 1)
        val snap = bundle.snapshots[idx]
        val label = if (snap.title.isNotEmpty()) localize(snap.title) else snap.stepType
        g.renderTooltip(font, Component.literal("${idx + 1}/$total · $label"), mouseX, mouseY)
    }

    // ==================== 输入转发与沙盒隔离 ====================

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        for (btn in buttons) {
            if (btn.enabled && btn.contains(mouseX, mouseY)) {
                playUiSound(HexSounds.ABACUS)
                btn.onClick()
                return true
            }
        }

        // 小屏弹出式札记浮层拦截鼠标点击（点击 ✕ 或外部遮罩关闭，内部拦截防穿透）
        if (knowledgeOpen && knowledgeW == 0) {
            val (ox, oy, ow, oh) = popupBounds()
            val hitCloseBtn = mouseX >= ox + ow - 18 && mouseX <= ox + ow - 2 && mouseY >= oy + 2 && mouseY <= oy + 16
            val insideBox = mouseX >= ox && mouseX < ox + ow && mouseY >= oy && mouseY < oy + oh
            if (hitCloseBtn || (!insideBox && mouseY >= bodyY && mouseY <= bodyY + bodyH)) {
                knowledgeOpen = false
                computeLayout()
                playUiSound(HexSounds.ABACUS)
                return true
            }
            if (insideBox) {
                return true
            }
        }

        // 点击时间轴直接跳转
        if (bundle.snapshots.isNotEmpty() &&
            mouseX >= timelineX && mouseX <= timelineX + timelineW &&
            mouseY >= timelineY - 3 && mouseY <= timelineY + timelineH + 3
        ) {
            val ratio = ((mouseX - timelineX) / timelineW.toDouble()).coerceIn(0.0, 0.999)
            stepTo((ratio * bundle.snapshots.size).toInt(), autoPlay = false)
            return true
        }

        if (sandboxMode && mouseX >= innerX && mouseX <= innerX + innerW && mouseY >= innerY && mouseY <= innerY + innerH) {
            return sandboxSpellcasting?.mouseClicked(mouseX, mouseY, button) ?: false
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (sandboxMode) {
            return sandboxSpellcasting?.mouseDragged(mouseX, mouseY, button, dragX, dragY) ?: false
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (sandboxMode) {
            return sandboxSpellcasting?.mouseReleased(mouseX, mouseY, button) ?: false
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (sandboxMode) {
            sandboxSpellcasting?.mouseMoved(mouseX, mouseY)
        }
        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val dir = if (scrollY > 0) -1 else 1
        if (mouseX >= leftX && mouseX <= leftX + leftW) {
            stackScroll = (stackScroll + dir).coerceAtLeast(0)
            return true
        }
        if (knowledgeOpen && (knowledgeW == 0 || mouseX >= knowledgeX)) {
            knowledgeScroll = (knowledgeScroll + dir * 14).coerceAtLeast(0)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // 按 hex-gui-writer 规范必须由本屏拦截 ESC，防止内嵌 GuiSpellcasting 直接 closeForReal()
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (knowledgeOpen && knowledgeW == 0) {
                knowledgeOpen = false
                computeLayout()
                return true
            }
            if (sandboxMode) {
                exitSandbox()
                return true
            }
            onClose()
            return true
        }
        if (sandboxMode) {
            if (sandboxSpellcasting?.keyPressed(keyCode, scanCode, modifiers) == true) return true
        } else {
            when (keyCode) {
                GLFW.GLFW_KEY_SPACE -> { togglePlay(); return true }
                GLFW.GLFW_KEY_LEFT -> { stepTo(currentStepIndex - 1, autoPlay = false); return true }
                GLFW.GLFW_KEY_RIGHT -> { stepTo(currentStepIndex + 1, autoPlay = false); return true }
                GLFW.GLFW_KEY_R -> { restart(); return true }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun onClose() {
        // 禁止调用 super.onClose() 覆盖父界面返回逻辑
        Minecraft.getInstance().soundManager.stop(HexSounds.CASTING_AMBIANCE.value().location, null)
        sandboxSpellcasting = null
        Minecraft.getInstance().setScreen(parentScreen)
    }

    private fun parsePattern(sig: String, startDirName: String): HexPattern? {
        if (sig.isEmpty()) return null
        return runCatching {
            patternFromAngles(sig, HexDir.valueOf(startDirName.uppercase()))
        }.getOrNull()
    }

    private fun localize(keyOrText: String): String {
        if (keyOrText.isEmpty()) return ""
        return if (I18n.exists(keyOrText)) I18n.get(keyOrText) else keyOrText
    }

    private fun text(key: String): String =
        if (I18n.exists(key)) I18n.get(key) else key
}
