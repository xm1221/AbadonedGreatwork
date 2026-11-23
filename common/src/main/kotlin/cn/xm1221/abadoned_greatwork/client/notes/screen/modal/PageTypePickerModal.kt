package cn.xm1221.abadoned_greatwork.client.notes.screen.modal

import cn.xm1221.abadoned_greatwork.client.gui.skin.BaseSkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteVisualHelpers
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.language.I18n
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 书页类型卡片网格选择器（隐藏底层 `patchouli:*` ID，按人类名称、图标与 1 行说明展示）。
 */
class PageTypePickerModal(
    initialType: NotePageType,
    onDismiss: () -> Unit,
    private val onPick: (NotePageType) -> Unit,
) : BaseSkinModal(
    title = Component.translatable("abadoned_greatwork.notes.picker.page_type"),
    regionKey = "page_type_modal",
    defaultW = 292,
    defaultH = 178,
    onDismiss = onDismiss,
) {
    private val types = NotePageType.entries
    private var selectedIdx = types.indexOf(initialType).coerceAtLeast(0)

    private val cols = 3
    private val cellW = 88
    private val cellH = 27
    private val gapX = 4
    private val gapY = 3

    private val gridX: Int get() = winX + 10
    private val gridY: Int get() = winY + 26

    override fun buildContent() {
        // 卡片网格使用自定义绘制与键鼠导航，无需额外控件
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val s = skin

        var hoveredIdx = -1
        for (idx in types.indices) {
            val col = idx % cols
            val row = idx / cols
            val cx = gridX + col * (cellW + gapX)
            val cy = gridY + row * (cellH + gapY)
            val hovered = mouseX >= cx && mouseX < cx + cellW && mouseY >= cy && mouseY < cy + cellH
            if (hovered) hoveredIdx = idx

            val spriteState = when {
                idx == selectedIdx -> "selected"
                hovered -> "hover"
                else -> "normal"
            }
            SkinRenderer.drawSprite(graphics, s, "card", cx, cy, cellW, cellH, spriteState)

            val type = types[idx]
            val iconKey = "icon.page.${NoteVisualHelpers.pageIconSuffix(type)}"
            SkinRenderer.drawSprite(graphics, s, iconKey, cx + 6, cy + (cellH - 12) / 2, 12, 12)

            val label = Component.translatable(type.langKey)
            val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(label, cellW - 24))
            val textCol = s.color(if (idx == selectedIdx) "title" else "text", 0xFF_F2EBF9.toInt())
            graphics.drawString(font, trimmed, cx + 21, cy + (cellH - 8) / 2, textCol, false)
        }

        val activeType = types.getOrElse(if (hoveredIdx >= 0) hoveredIdx else selectedIdx) { NotePageType.TEXT }
        val descBoxY = winY + winH - 26
        SkinRenderer.drawSprite(graphics, s, "sub_panel", winX + 10, descBoxY, winW - 20, 18)

        val descKey = "abadoned_greatwork.notes.page_type.desc.${NoteVisualHelpers.pageIconSuffix(activeType)}"
        val descComp = Component.literal(I18n.get(descKey))
        val descTrimmed = Language.getInstance().getVisualOrder(font.substrByWidth(descComp, winW - 30))
        graphics.drawString(font, descTrimmed, winX + 15, descBoxY + 5, s.color("text_dim", 0xFF_A293B8.toInt()), false)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX < winX || mouseX >= winX + winW || mouseY < winY || mouseY >= winY + winH) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        if (button == 0) {
            for (idx in types.indices) {
                val col = idx % cols
                val row = idx / cols
                val cx = gridX + col * (cellW + gapX)
                val cy = gridY + row * (cellH + gapY)
                if (mouseX >= cx && mouseX < cx + cellW && mouseY >= cy && mouseY < cy + cellH) {
                    val picked = types[idx]
                    dismiss()
                    onPick(picked)
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dismiss()
            return true
        }
        when (keyCode) {
            GLFW.GLFW_KEY_LEFT -> selectedIdx = (selectedIdx - 1).coerceAtLeast(0)
            GLFW.GLFW_KEY_RIGHT -> selectedIdx = (selectedIdx + 1).coerceAtMost(types.lastIndex)
            GLFW.GLFW_KEY_UP -> selectedIdx = (selectedIdx - cols).coerceAtLeast(0)
            GLFW.GLFW_KEY_DOWN -> selectedIdx = (selectedIdx + cols).coerceAtMost(types.lastIndex)
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                val picked = types[selectedIdx]
                dismiss()
                onPick(picked)
                return true
            }
            else -> return super.keyPressed(keyCode, scanCode, modifiers)
        }
        return true
    }
}
