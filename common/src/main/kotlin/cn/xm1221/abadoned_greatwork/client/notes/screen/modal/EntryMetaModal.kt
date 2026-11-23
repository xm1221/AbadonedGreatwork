package cn.xm1221.abadoned_greatwork.client.notes.screen.modal

import cn.xm1221.abadoned_greatwork.client.gui.skin.BaseSkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteVisualHelpers
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 条目图标与标题颜色设置浮层。
 * 隐藏原始物品 ID 文本与 `#RRGGBB` 心智负担：提供可视化物品选择器、手持物一键填充和 12 色常用色板。
 */
class EntryMetaModal(
    initialIcon: String,
    initialColor: String,
    onDismiss: () -> Unit,
    private val onApply: (icon: String, color: String) -> Unit,
) : BaseSkinModal(
    title = Component.translatable("abadoned_greatwork.notes.meta.title"),
    regionKey = "entry_meta_modal",
    defaultW = 248,
    defaultH = 168,
    onDismiss = onDismiss,
) {
    private var currentIcon: String = initialIcon
    private var currentColor: String = normalizeHex(initialColor)

    private lateinit var hexField: SkinTextField
    private var useHeldBtn: SkinButton? = null
    private var childModal: SkinModal? = null

    private val swatches = listOf(
        "9B59B6", "54398A", "E74C3C", "E67E22",
        "F1C40F", "2ECC71", "1ABC9C", "3498DB",
        "8E44AD", "ECF0F1", "95A5A6", "D35400",
    )

    private val swatchStartX: Int get() = winX + 12
    private val swatchY: Int get() = winY + 90
    private val swatchSize = 16
    private val swatchStep = 18

    override fun buildContent() {
        val mc = Minecraft.getInstance()
        val font = mc.font

        addWidget(
            SkinButton({ skin }, winX + 44, winY + 46, 92, 16, Component.translatable("abadoned_greatwork.notes.btn.choose_icon"), "button") {
                val modal = ItemPickerModal(
                    onDismiss = { childModal = null },
                    onPick = { picked ->
                        currentIcon = picked
                        childModal = null
                    },
                )
                modal.setDismissHook { if (childModal === modal) childModal = null }
                modal.init(mc.window.guiScaledWidth, mc.window.guiScaledHeight)
                childModal = modal
            }
        )

        useHeldBtn = addWidget(
            SkinButton({ skin }, winX + 140, winY + 46, 96, 16, Component.translatable("abadoned_greatwork.notes.btn.use_held_item"), "button") {
                val held = mc.player?.mainHandItem
                if (held != null && !held.isEmpty) {
                    currentIcon = BuiltInRegistries.ITEM.getKey(held.item).toString()
                }
            }
        )

        hexField = addWidget(
            SkinTextField({ skin }, font, winX + 24, winY + 114, 72, 16, Component.literal("HEX"), "dark_field")
        )
        hexField.setMaxLength(6)
        hexField.setHint(Component.literal("9B59B6"))
        hexField.value = currentColor
        hexField.setResponder { raw ->
            val cleaned = raw.trim().removePrefix("#").uppercase()
            if (HEX_6_REGEX.matches(cleaned)) {
                currentColor = cleaned
            }
        }

        addWidget(
            SkinButton({ skin }, winX + winW - 134, winY + winH - 24, 58, 16, Component.translatable("abadoned_greatwork.notes.btn.cancel"), "button") {
                dismiss()
            }
        )
        addWidget(
            SkinButton({ skin }, winX + winW - 72, winY + winH - 24, 62, 16, Component.translatable("abadoned_greatwork.notes.btn.confirm"), "button_accent") {
                dismiss()
                onApply(currentIcon, normalizeHex(currentColor))
            }
        )
    }

    override fun tick() {
        childModal?.tick()
    }

    override fun shouldSuppressWidgetHover(): Boolean = childModal != null

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val mc = Minecraft.getInstance()
        val font = mc.font
        val s = skin

        val heldEmpty = mc.player?.mainHandItem?.isEmpty ?: true
        useHeldBtn?.active = !heldEmpty

        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.meta.icon"), winX + 12, winY + 25, s.color("text_dim", 0xFF_A293B8.toInt()), false)

        SkinRenderer.drawSprite(graphics, s, "dark_slot", winX + 12, winY + 36, 28, 28)
        val stack = NoteVisualHelpers.resolveItemStack(currentIcon)
        graphics.renderItem(stack, winX + 18, winY + 42)
        val nameTrimmed = Language.getInstance().getVisualOrder(font.substrByWidth(stack.hoverName, winW - 60))
        graphics.drawString(font, nameTrimmed, winX + 45, winY + 35, s.color("text", 0xFF_F2EBF9.toInt()), false)

        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.meta.color"), winX + 12, winY + 76, s.color("text_dim", 0xFF_A293B8.toInt()), false)

        for (i in swatches.indices) {
            val hex = swatches[i]
            val sx = swatchStartX + i * swatchStep
            val sy = swatchY
            val rgb = GuiSkin.parseHexColor("FF$hex") ?: 0xFF_9B59B6.toInt()
            val selected = hex.equals(currentColor, ignoreCase = true)
            val hovered = childModal == null && mouseX >= sx && mouseX < sx + swatchSize && mouseY >= sy && mouseY < sy + swatchSize
            val borderCol = when {
                selected -> 0xFF_FFFFFF.toInt()
                hovered -> 0xFF_DCC6F8.toInt()
                else -> 0xFF_2A2136.toInt()
            }
            graphics.fill(sx, sy, sx + swatchSize, sy + swatchSize, borderCol)
            graphics.fill(sx + 1, sy + 1, sx + swatchSize - 1, sy + swatchSize - 1, rgb)
        }

        graphics.drawString(font, "#", winX + 13, winY + 118, s.color("text_dim", 0xFF_A293B8.toInt()), false)
        val previewRgb = GuiSkin.parseHexColor("FF$currentColor") ?: 0xFF_9B59B6.toInt()
        graphics.fill(winX + 102, winY + 114, winX + 118, winY + 130, 0xFF_2A2136.toInt())
        graphics.fill(winX + 103, winY + 115, winX + 117, winY + 129, previewRgb)
        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.meta.hex_hint"), winX + 124, winY + 118, s.color("text_dim", 0xFF_A293B8.toInt()), false)
    }

    override fun renderOverlay(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        childModal?.let { sub ->
            graphics.pose().pushPose()
            graphics.pose().translate(0f, 0f, 120f)
            sub.render(graphics, mouseX, mouseY, partialTicks)
            graphics.pose().popPose()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        childModal?.let { return it.mouseClicked(mouseX, mouseY, button) }
        if (mouseX < winX || mouseX >= winX + winW || mouseY < winY || mouseY >= winY + winH) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        if (button == 0) {
            for (i in swatches.indices) {
                val sx = swatchStartX + i * swatchStep
                val sy = swatchY
                if (mouseX >= sx && mouseX < sx + swatchSize && mouseY >= sy && mouseY < sy + swatchSize) {
                    currentColor = swatches[i]
                    hexField.value = currentColor
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean =
        childModal?.mouseReleased(mouseX, mouseY, button) ?: super.mouseReleased(mouseX, mouseY, button)

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean =
        childModal?.mouseDragged(mouseX, mouseY, button, dragX, dragY) ?: super.mouseDragged(mouseX, mouseY, button, dragX, dragY)

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        childModal?.mouseScrolled(mouseX, mouseY, scrollX, scrollY) ?: super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        childModal?.let { return it.keyPressed(keyCode, scanCode, modifiers) }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dismiss()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        childModal?.charTyped(codePoint, modifiers) ?: super.charTyped(codePoint, modifiers)

    override fun onClose() {
        childModal?.onClose()
        childModal = null
    }

    companion object {
        private val HEX_6_REGEX = Regex("^[0-9A-F]{6}$")

        private fun normalizeHex(raw: String): String {
            val cleaned = raw.trim().removePrefix("#").uppercase()
            return if (HEX_6_REGEX.matches(cleaned)) cleaned else "9B59B6"
        }
    }
}
