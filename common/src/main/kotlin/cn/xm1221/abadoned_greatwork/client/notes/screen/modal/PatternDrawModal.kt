package cn.xm1221.abadoned_greatwork.client.notes.screen.modal

import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType
import at.petrak.hexcasting.api.casting.math.HexCoord
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.utils.coordToPx
import at.petrak.hexcasting.api.utils.pxToCoord
import at.petrak.hexcasting.client.gui.GuiSpellcasting
import at.petrak.hexcasting.common.lib.HexSounds
import cn.xm1221.abadoned_greatwork.client.gui.skin.BaseSkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteVisualHelpers
import cn.xm1221.abadoned_greatwork.client.ponder.PonderSpellcastingAccess
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec2
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 嵌入原生六边形法杖网格（`GuiSpellcasting` + `PonderSpellcastingAccess`）的手绘图案弹窗。
 * 完全禁用高级文本签名输入，仅通过鼠标连点成纹来录制 `HexPattern`。
 */
class PatternDrawModal(
    private val initialPattern: HexPattern?,
    onDismiss: () -> Unit,
    private val onConfirm: (HexPattern) -> Unit,
) : BaseSkinModal(
    title = Component.translatable("abadoned_greatwork.notes.draw.title"),
    regionKey = "pattern_draw_modal",
    defaultW = 264,
    defaultH = 196,
    onDismiss = onDismiss,
) {
    private var spellcasting: GuiSpellcasting? = null
    private var capturedPattern: HexPattern? = initialPattern
    private var confirmBtn: SkinButton? = null
    private var startedStrokeInPad = false

    private val padX: Int get() = winX + 10
    private val padY: Int get() = winY + 26
    private val padW: Int get() = winW - 20
    private val padH: Int get() = winH - 68
    private val hexSize = 18f

    override fun buildContent() {
        val mc = Minecraft.getInstance()
        val sc = GuiSpellcasting(InteractionHand.MAIN_HAND, ArrayList(), emptyList(), null, 0)
        sc.init(mc, mc.window.guiScaledWidth, mc.window.guiScaledHeight)
        mc.soundManager.stop(HexSounds.CASTING_AMBIANCE.location, null)

        val access = sc as? PonderSpellcastingAccess
        access?.`setSandboxMode$abw`(true)
        access?.`setHexSizeOverride$abw`(hexSize)
        access?.`setCoordsOffsetOverride$abw`(Vec2(padX + padW / 2f, padY + padH / 2f))
        access?.`setOnDrawPattern$abw` { pattern ->
            capturedPattern = pattern
            confirmBtn?.active = true
            access.`setLastPatternType$abw`(ResolvedPatternType.EVALUATED)
        }
        spellcasting = sc
        startedStrokeInPad = false

        addWidget(
            SkinButton({ skin }, winX + 10, winY + winH - 23, 56, 16, Component.translatable("abadoned_greatwork.notes.draw.clear"), "button") {
                (spellcasting as? PonderSpellcastingAccess)?.`clearPatterns$abw`()
                capturedPattern = null
                confirmBtn?.active = false
                startedStrokeInPad = false
            }
        )
        addWidget(
            SkinButton({ skin }, winX + winW - 134, winY + winH - 23, 58, 16, Component.translatable("abadoned_greatwork.notes.btn.cancel"), "button") {
                dismiss()
            }
        )
        confirmBtn = addWidget(
            SkinButton({ skin }, winX + winW - 72, winY + winH - 23, 62, 16, Component.translatable("abadoned_greatwork.notes.btn.confirm"), "button_accent") {
                val result = capturedPattern ?: return@SkinButton
                dismiss()
                onConfirm(result)
            }
        )
        confirmBtn?.active = (capturedPattern != null)
    }

    override fun tick() {
        // 沙盒模式下跳过原版 GuiSpellcasting.tick() 的法杖手持检测
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val s = skin

        SkinRenderer.drawSprite(graphics, s, "sub_panel", padX, padY, padW, padH)

        graphics.enableScissor(padX + 2, padY + 2, padX + padW - 2, padY + padH - 2)
        renderLatticeDots(graphics)

        val sc = spellcasting
        val access = sc as? PonderSpellcastingAccess
        val pat = capturedPattern
        if (pat != null && (access?.`patternCount$abw`() ?: 0) == 0) {
            NoteVisualHelpers.renderHexPattern(
                graphics = graphics,
                pattern = pat,
                x = padX + 16,
                y = padY + 12,
                w = padW - 32,
                h = padH - 24,
                stroke = 2.8,
                hexSize = hexSize.toDouble(),
                key = "abw_draw_init",
            )
        }

        if (sc != null) {
            sc.render(graphics, mouseX, mouseY, partialTicks)
            RenderSystem.enableCull()
            RenderSystem.disableDepthTest()
        }
        graphics.disableScissor()

        val statusY = winY + winH - 38
        val statusComp = if (pat != null) {
            Component.translatable("abadoned_greatwork.notes.draw.captured", pat.startDir.name, pat.anglesSignature().ifEmpty { "-" })
        } else {
            Component.translatable("abadoned_greatwork.notes.draw.hint")
        }
        val statusColor = s.color(if (pat != null) "ok" else "text_dim", 0xFF_A293B8.toInt())
        graphics.drawString(font, font.plainSubstrByWidth(statusComp.string, winW - 20), winX + 10, statusY, statusColor, false)
    }

    private fun renderLatticeDots(graphics: GuiGraphics) {
        val offset = Vec2(padX + padW / 2f, padY + padH / 2f)
        val c0 = pxToCoord(Vec2(padX.toFloat(), padY.toFloat()), hexSize, offset)
        val c1 = pxToCoord(Vec2((padX + padW).toFloat(), (padY + padH).toFloat()), hexSize, offset)
        val q0 = min(c0.q, c1.q) - 1
        val q1 = max(c0.q, c1.q) + 1
        val r0 = min(c0.r, c1.r) - 1
        val r1 = max(c0.r, c1.r) + 1
        for (q in q0..q1) {
            for (r in r0..r1) {
                val p = coordToPx(HexCoord(q, r), hexSize, offset)
                val ix = p.x.roundToInt()
                val iy = p.y.roundToInt()
                if (ix in (padX + 4)..(padX + padW - 5) && iy in (padY + 4)..(padY + padH - 5)) {
                    graphics.fill(ix - 1, iy - 1, ix + 1, iy + 1, 0x3A_A896CE)
                }
            }
        }
    }

    private fun isInsidePad(mx: Double, my: Double): Boolean =
        mx >= padX && mx < padX + padW && my >= padY && my < padY + padH

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX < winX || mouseX >= winX + winW || mouseY < winY || mouseY >= winY + winH) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        for (w in widgets.asReversed()) {
            if (w.mouseClicked(mouseX, mouseY, button)) {
                setModalFocus(w)
                return true
            }
        }
        if (button == 0 && isInsidePad(mouseX, mouseY)) {
            startedStrokeInPad = true
            spellcasting?.mouseClicked(mouseX, mouseY, button)
            setModalFocus(null)
            return true
        }
        setModalFocus(null)
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (startedStrokeInPad || isInsidePad(mouseX, mouseY)) {
            spellcasting?.mouseMoved(mouseX, mouseY)
        }
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true
        if (startedStrokeInPad || isInsidePad(mouseX, mouseY)) {
            return spellcasting?.mouseDragged(mouseX, mouseY, button, dragX, dragY) ?: true
        }
        return false
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val handledWidget = super.mouseReleased(mouseX, mouseY, button)
        if (startedStrokeInPad) {
            spellcasting?.mouseReleased(mouseX, mouseY, button)
            startedStrokeInPad = false
            return true
        }
        return handledWidget
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // 先于嵌入 GuiSpellcasting 拦截 ESC，避免关闭整层编辑器
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dismiss()
            return true
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && capturedPattern != null) {
            val res = capturedPattern!!
            dismiss()
            onConfirm(res)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun onClose() {
        Minecraft.getInstance().soundManager.stop(HexSounds.CASTING_AMBIANCE.location, null)
        spellcasting = null
    }
}
