package cn.xm1221.abadoned_greatwork.client.notes.screen.modal

import at.petrak.hexcasting.api.casting.iota.IotaType
import cn.xm1221.abadoned_greatwork.client.gui.skin.BaseSkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.locale.Language
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 退出编辑器时的三态未保存确认浮层：放弃修改 / 继续编辑 / 保存并退出。
 */
class DiscardConfirmModal(
    onDismiss: () -> Unit,
    private val onDiscardAndClose: () -> Unit,
    private val onSaveAndClose: () -> Unit,
) : BaseSkinModal(
    title = Component.translatable("abadoned_greatwork.notes.modal.discard.title"),
    regionKey = "confirm_modal",
    defaultW = 246,
    defaultH = 98,
    onDismiss = onDismiss,
) {
    override fun buildContent() {
        val btnY = winY + winH - 25
        addWidget(
            SkinButton({ skin }, winX + 10, btnY, 68, 16, Component.translatable("abadoned_greatwork.notes.btn.discard_close"), "button_danger") {
                dismiss()
                onDiscardAndClose()
            }
        )
        addWidget(
            SkinButton({ skin }, winX + 82, btnY, 72, 16, Component.translatable("abadoned_greatwork.notes.btn.keep_editing"), "button") {
                dismiss()
            }
        )
        addWidget(
            SkinButton({ skin }, winX + 158, btnY, 78, 16, Component.translatable("abadoned_greatwork.notes.btn.save_close"), "button_accent") {
                dismiss()
                onSaveAndClose()
            }
        )
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val font = Minecraft.getInstance().font
        val msg = Component.translatable("abadoned_greatwork.notes.modal.discard.body")
        val lines = font.split(msg, winW - 22)
        var y = winY + 30
        for (line in lines.take(3)) {
            graphics.drawString(font, line, winX + 11, y, skin.color("text", 0xFF_F2EBF9.toInt()), false)
            y += 10
        }
    }
}

/**
 * 条目删除二次确认浮层。
 */
class DeleteConfirmModal(
    private val entryTitle: String,
    onDismiss: () -> Unit,
    private val onConfirmDelete: () -> Unit,
) : BaseSkinModal(
    title = Component.translatable("abadoned_greatwork.notes.modal.delete.title"),
    regionKey = "confirm_modal",
    defaultW = 246,
    defaultH = 98,
    onDismiss = onDismiss,
) {
    override fun buildContent() {
        val btnY = winY + winH - 25
        addWidget(
            SkinButton({ skin }, winX + winW - 140, btnY, 62, 16, Component.translatable("abadoned_greatwork.notes.btn.cancel"), "button") {
                dismiss()
            }
        )
        addWidget(
            SkinButton({ skin }, winX + winW - 74, btnY, 64, 16, Component.translatable("abadoned_greatwork.notes.btn.delete"), "button_danger") {
                dismiss()
                onConfirmDelete()
            }
        )
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val font = Minecraft.getInstance().font
        val msg = Component.translatable("abadoned_greatwork.notes.modal.delete.body", entryTitle.ifBlank { "?" })
        val lines = font.split(msg, winW - 22)
        var y = winY + 30
        for (line in lines.take(3)) {
            graphics.drawString(font, line, winX + 11, y, skin.color("text", 0xFF_F2EBF9.toInt()), false)
            y += 10
        }
    }
}

/**
 * 高级 Iota SNBT 粘贴与预览浮层：严格客户端 tag-only 校验与展示，不触发任何服务端 iota 反序列化。
 */
class SnbtPasteModal(
    onDismiss: () -> Unit,
    private val onAddIota: (CompoundTag) -> Unit,
) : BaseSkinModal(
    title = Component.translatable("abadoned_greatwork.notes.modal.snbt.title"),
    regionKey = "snbt_modal",
    defaultW = 260,
    defaultH = 108,
    onDismiss = onDismiss,
) {
    private lateinit var snbtField: SkinTextField
    private var addBtn: SkinButton? = null
    private var parsedTag: CompoundTag? = null
    private var previewDisplay: Component? = null
    private var previewColor: Int = 0xFF_F2EBF9.toInt()
    private var errorText: Component? = null

    override fun buildContent() {
        val font = Minecraft.getInstance().font
        snbtField = addWidget(
            SkinTextField({ skin }, font, winX + 10, winY + 36, winW - 20, 16, Component.literal("SNBT"), "dark_field")
        )
        snbtField.setMaxLength(2048)
        snbtField.setHint(Component.literal("{\"hexcasting:type\":\"hexcasting:double\", \"hexcasting:data\":42.0d}"))
        snbtField.setResponder { raw -> validateSnbt(raw) }

        val btnY = winY + winH - 25
        addWidget(
            SkinButton({ skin }, winX + winW - 134, btnY, 58, 16, Component.translatable("abadoned_greatwork.notes.btn.cancel"), "button") {
                dismiss()
            }
        )
        addBtn = addWidget(
            SkinButton({ skin }, winX + winW - 72, btnY, 62, 16, Component.translatable("abadoned_greatwork.notes.btn.add"), "button_accent") {
                val tag = parsedTag ?: return@SkinButton
                dismiss()
                onAddIota(tag)
            }
        )
        addBtn?.active = false
        setModalFocus(snbtField)
    }

    private fun validateSnbt(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            parsedTag = null
            previewDisplay = null
            errorText = null
            addBtn?.active = false
            return
        }
        val tag = runCatching { TagParser.parseTag(trimmed) }.getOrNull()
        if (tag == null) {
            parsedTag = null
            previewDisplay = null
            errorText = Component.translatable("abadoned_greatwork.notes.modal.snbt.invalid")
            addBtn?.active = false
            return
        }
        val type = runCatching { IotaType.getTypeFromTag(tag) }.getOrNull()
        if (type == null) {
            parsedTag = null
            previewDisplay = null
            errorText = Component.translatable("abadoned_greatwork.notes.modal.snbt.unknown_type")
            addBtn?.active = false
            return
        }
        parsedTag = tag
        previewColor = (runCatching { IotaType.getColor(tag) }.getOrDefault(0xAAAAAA)) or 0xFF_000000.toInt()
        previewDisplay = runCatching { IotaType.getDisplay(tag) }.getOrElse { Component.literal(trimmed) }
        errorText = null
        addBtn?.active = true
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val font = Minecraft.getInstance().font
        val s = skin
        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.modal.snbt.prompt"), winX + 10, winY + 24, s.color("text_dim", 0xFF_A293B8.toInt()), false)

        val previewY = winY + 57
        SkinRenderer.drawSprite(graphics, s, "sub_panel", winX + 10, previewY, winW - 20, 18)
        when {
            previewDisplay != null -> {
                graphics.fill(winX + 14, previewY + 4, winX + 17, previewY + 14, previewColor)
                val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(previewDisplay!!, winW - 36))
                graphics.drawString(font, trimmed, winX + 21, previewY + 5, s.color("text", 0xFF_F2EBF9.toInt()), false)
            }
            errorText != null -> {
                val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(errorText!!, winW - 30))
                graphics.drawString(font, trimmed, winX + 15, previewY + 5, s.color("error", 0xFF_EC6270.toInt()), false)
            }
            else -> {
                graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.modal.snbt.hint"), winX + 15, previewY + 5, s.color("text_dim", 0xFF_A293B8.toInt()), false)
            }
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && parsedTag != null) {
            val tag = parsedTag!!
            dismiss()
            onAddIota(tag)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }
}
