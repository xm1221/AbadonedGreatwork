package cn.xm1221.abadoned_greatwork.client.gui.skin

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.font.TextFieldHelper
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW

/**
 * 贴图驱动的三态按钮（支持普通/强调/危险样式与 12×12 像素图标）。
 */
class SkinButton(
    private val skinSupplier: () -> GuiSkin,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    msg: Component,
    private val spriteKey: String = "button",
    private val iconKey: String? = null,
    tooltipText: Component? = null,
    onPress: OnPress,
) : Button(x, y, w, h, msg, onPress, DEFAULT_NARRATION) {

    init {
        if (tooltipText != null) {
            tooltip = Tooltip.create(tooltipText)
        }
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val skin = skinSupplier()
        val state = when {
            !active -> "disabled"
            isHoveredOrFocused -> "hover"
            else -> "normal"
        }
        SkinRenderer.drawSprite(graphics, skin, spriteKey, x, y, width, height, state)

        val font = Minecraft.getInstance().font
        val textColor = if (active) {
            skin.color("button_text", 0xFF_F4EEFC.toInt())
        } else {
            skin.color("button_text_disabled", 0xFF_7A7088.toInt())
        }

        val label = message
        val hasLabel = label.string.isNotEmpty()
        if (iconKey != null && !hasLabel) {
            val ix = x + (width - 12) / 2
            val iy = y + (height - 12) / 2
            SkinRenderer.drawSprite(graphics, skin, iconKey, ix, iy, 12, 12)
        } else if (iconKey != null) {
            val totalW = 12 + 3 + font.width(label)
            val startX = x + (width - totalW) / 2
            SkinRenderer.drawSprite(graphics, skin, iconKey, startX, y + (height - 12) / 2, 12, 12)
            graphics.drawString(font, label, startX + 15, y + (height - 8) / 2, textColor, false)
        } else {
            val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(label, (width - 6).coerceAtLeast(8)))
            graphics.drawCenteredString(font, trimmed, x + width / 2, y + (height - 8) / 2, textColor)
        }
    }
}

/**
 * 贴图驱动的单行输入框（隐藏原生边框，底层绘制 `text_field` 九宫格精灵）。
 */
class SkinTextField(
    private val skinSupplier: () -> GuiSkin,
    font: Font,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    label: Component,
    private val spriteKey: String = "text_field",
) : EditBox(font, x + 4, y + (h - 8) / 2, (w - 8).coerceAtLeast(8), h, label) {

    private val boxX = x
    private val boxY = y
    private val boxW = w
    private val boxH = h

    init {
        setBordered(false)
        setTextColor(0xFF_EFE8FA.toInt())
        setTextColorUneditable(0xFF_8D82A0.toInt())
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val skin = skinSupplier()
        SkinRenderer.drawSprite(graphics, skin, spriteKey, boxX, boxY, boxW, boxH)
        super.renderWidget(graphics, mouseX, mouseY, partialTicks)
    }
}

/**
 * 贴图驱动的多行书页文本区：
 * - 逻辑行宽默认 `116`（与 Patchouli `GuiBook.PAGE_WIDTH` 完全一致），实现所见即所得折行。
 * - 支持 `TextFieldHelper` 光标/选区/剪贴板、滚轮翻行、点击精确定位光标。
 */
class SkinTextArea(
    private val skinSupplier: () -> GuiSkin,
    var x: Int,
    var y: Int,
    var w: Int,
    var h: Int,
    private val getter: () -> String,
    private val setter: (String) -> Unit,
    private val maxLength: Int = 4096,
    private val paperSprite: String = "page_paper",
    private val textColorKey: String = "paper_text",
    private val defaultTextColor: Int = 0xFF_2B2016.toInt(),
) {
    private val font: Font get() = Minecraft.getInstance().font

    private val helper = TextFieldHelper(
        getter,
        setter,
        { Minecraft.getInstance().keyboardHandler.clipboard },
        { s -> Minecraft.getInstance().keyboardHandler.clipboard = s },
        { s -> s.length <= maxLength },
    )

    var focused: Boolean = false
    private var scrollLine: Int = 0
    private val padding = 8

    private val innerW: Int get() = (w - padding * 2).coerceAtLeast(40)
    private val innerH: Int get() = (h - padding * 2).coerceAtLeast(18)
    /** 保证每行与 Patchouli 书页等宽 (116px) */
    private val scale: Float get() = (innerW.toFloat() / PAGE_LOGICAL_WIDTH.toFloat()).coerceIn(0.75f, 1.35f)
    private val visibleLines: Int get() = ((innerH / scale) / LINE_HEIGHT).toInt().coerceAtLeast(1)

    fun insertToken(token: String) {
        helper.insertText(token)
        ensureCaretVisible()
    }

    fun resetCursor() {
        helper.setCursorPos(0)
        helper.setSelectionPos(0)
        scrollLine = 0
    }

    fun isMouseOver(mx: Double, my: Double): Boolean =
        mx >= x && mx < x + w && my >= y && my < y + h

    fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        if (button != 0) return false
        if (!isMouseOver(mx, my)) {
            focused = false
            return false
        }
        focused = true
        val lx = ((mx - x - padding) / scale).toInt().coerceAtLeast(0)
        val ly = ((my - y - padding) / scale).toInt().coerceAtLeast(0)
        val visRow = ly / LINE_HEIGHT
        val targetRow = (scrollLine + visRow).coerceAtLeast(0)
        val text = getter()
        val lines = wrapLines(text)
        val clampedRow = targetRow.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val lineText = lines.getOrNull(clampedRow) ?: ""
        val col = font.splitter.plainIndexAtWidth(lineText, lx, Style.EMPTY)
        val pos = (rowToTextOffset(text, clampedRow) + col).coerceIn(0, text.length)
        helper.setCursorPos(pos)
        helper.setSelectionPos(pos)
        return true
    }

    fun mouseScrolled(mx: Double, my: Double, delta: Double): Boolean {
        if (!isMouseOver(mx, my)) return false
        val lines = wrapLines(getter())
        val maxScroll = (lines.size - visibleLines).coerceAtLeast(0)
        scrollLine = (scrollLine - delta.toInt()).coerceIn(0, maxScroll)
        return true
    }

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!focused) return false
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            helper.insertText("\n")
            ensureCaretVisible()
            return true
        }
        val handled = helper.keyPressed(keyCode)
        if (handled) ensureCaretVisible()
        return handled
    }

    fun charTyped(ch: Char, modifiers: Int): Boolean {
        if (!focused) return false
        if (ch == '\n' || ch == '\r') {
            helper.insertText("\n")
            ensureCaretVisible()
            return true
        }
        val handled = helper.charTyped(ch)
        if (handled) ensureCaretVisible()
        return handled
    }

    private fun ensureCaretVisible() {
        val text = getter()
        val lines = wrapLines(text)
        val (row, _) = cursorToRowCol(text, helper.getCursorPos().coerceIn(0, text.length))
        val maxScroll = (lines.size - visibleLines).coerceAtLeast(0)
        if (row < scrollLine) scrollLine = row
        if (row >= scrollLine + visibleLines) scrollLine = row - visibleLines + 1
        scrollLine = scrollLine.coerceIn(0, maxScroll)
    }

    fun render(graphics: GuiGraphics) {
        val skin = skinSupplier()
        SkinRenderer.drawSprite(graphics, skin, paperSprite, x, y, w, h)

        val text = getter()
        val lines = wrapLines(text)
        val maxScroll = (lines.size - visibleLines).coerceAtLeast(0)
        scrollLine = scrollLine.coerceIn(0, maxScroll)

        val textColor = skin.color(textColorKey, defaultTextColor)
        val caretColor = skin.color("caret", 0xFF_6B2FA0.toInt())
        val selColor = skin.color("selection", 0x55_8E44AD)

        val cursor = helper.getCursorPos().coerceIn(0, text.length)
        val sel = helper.getSelectionPos().coerceIn(0, text.length)
        val selMin = minOf(cursor, sel)
        val selMax = maxOf(cursor, sel)
        val starts = lineStarts(text)

        graphics.pose().pushPose()
        graphics.pose().translate((x + padding).toFloat(), (y + padding).toFloat(), 0f)
        graphics.pose().scale(scale, scale, 1f)

        val endLine = minOf(lines.size, scrollLine + visibleLines)
        for (i in scrollLine until endLine) {
            val visY = (i - scrollLine) * LINE_HEIGHT
            val lineStr = lines[i]
            val lineStart = starts.getOrElse(i) { text.length }
            val lineEnd = lineStart + lineStr.length

            if (selMin < selMax && selMax > lineStart && selMin <= lineEnd) {
                val sCol = (selMin - lineStart).coerceIn(0, lineStr.length)
                val eCol = (selMax - lineStart).coerceIn(0, lineStr.length)
                val sx = font.width(lineStr.substring(0, sCol))
                val ex = font.width(lineStr.substring(0, eCol))
                if (ex > sx) {
                    graphics.fill(sx, visY, ex, visY + LINE_HEIGHT, selColor)
                }
            }

            graphics.drawString(font, lineStr, 0, visY, textColor, false)
        }

        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            val (row, col) = cursorToRowCol(text, cursor)
            val visRow = row - scrollLine
            if (visRow in 0 until visibleLines) {
                val lineText = lines.getOrNull(row) ?: ""
                val caretX = font.width(lineText.substring(0, col.coerceIn(0, lineText.length)))
                val caretY = visRow * LINE_HEIGHT
                graphics.fill(caretX, caretY, caretX + 1, caretY + LINE_HEIGHT, caretColor)
            }
        }
        graphics.pose().popPose()

        // 简易滚动条提示
        if (lines.size > visibleLines) {
            val sbX = x + w - 6
            val sbY = y + 6
            val sbH = (h - 12).coerceAtLeast(16)
            SkinRenderer.drawSprite(graphics, skin, "scrollbar_track", sbX, sbY, 4, sbH)
            val thumbH = maxOf(8, sbH * visibleLines / lines.size)
            val thumbY = sbY + ((sbH - thumbH) * scrollLine / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, skin, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }
    }

    private fun wrapLines(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val splitter = font.splitter
        val out = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val nl = remaining.indexOf('\n')
            val limit = splitter.plainIndexAtWidth(remaining, PAGE_LOGICAL_WIDTH, Style.EMPTY).coerceAtLeast(1)
            val end = if (nl in 0 until limit) nl else limit.coerceAtMost(remaining.length)
            out.add(remaining.substring(0, end))
            val consumed = if (nl in 0 until limit) end + 1 else end
            remaining = remaining.substring(consumed)
            if (remaining.isEmpty() && consumed > 0 && text.endsWith('\n')) {
                out.add("")
            }
        }
        return out
    }

    private fun lineStarts(text: String): List<Int> {
        if (text.isEmpty()) return listOf(0)
        val splitter = font.splitter
        val starts = mutableListOf(0)
        var remaining = text
        var consumed = 0
        while (remaining.isNotEmpty()) {
            val nl = remaining.indexOf('\n')
            val limit = splitter.plainIndexAtWidth(remaining, PAGE_LOGICAL_WIDTH, Style.EMPTY).coerceAtLeast(1)
            val end = if (nl in 0 until limit) nl else limit.coerceAtMost(remaining.length)
            val seg = if (nl in 0 until limit) end + 1 else end
            consumed += seg
            if (consumed < text.length) starts.add(consumed)
            remaining = remaining.substring(seg)
            if (remaining.isEmpty() && seg > 0 && text.endsWith('\n')) {
                starts.add(consumed)
                break
            }
        }
        return starts
    }

    private fun rowToTextOffset(text: String, row: Int): Int =
        lineStarts(text).getOrElse(row) { text.length }

    private fun cursorToRowCol(text: String, cursor: Int): Pair<Int, Int> {
        val starts = lineStarts(text)
        var row = 0
        for (i in starts.indices) {
            if (i + 1 < starts.size && cursor >= starts[i + 1]) row = i + 1 else break
        }
        return row to (cursor - starts[row]).coerceAtLeast(0)
    }

    companion object {
        const val PAGE_LOGICAL_WIDTH = 116
        const val LINE_HEIGHT = 9
    }
}

data class SuggestOption(
    val value: String,
    val label: String,
)

/**
 * 贴图驱动的悬浮搜索下拉列表（用于物品、实体、配方、操作与 JSON 文件选择）。
 */
class SkinSuggestBox(
    private val skinSupplier: () -> GuiSkin,
) {
    var open: Boolean = false
        private set
    var x: Int = 0
    var y: Int = 0
    var w: Int = 180
    var h: Int = 110

    private var allOptions: List<SuggestOption> = emptyList()
    private var filtered: List<SuggestOption> = emptyList()
    private var scroll: Int = 0
    private var onPick: ((SuggestOption) -> Unit)? = null

    fun show(x: Int, y: Int, w: Int, h: Int, options: List<SuggestOption>, query: String, onSelect: (SuggestOption) -> Unit) {
        this.x = x
        this.y = y
        this.w = w
        this.h = h
        this.allOptions = options
        this.onPick = onSelect
        this.scroll = 0
        this.open = true
        updateFilter(query)
    }

    fun close() {
        open = false
    }

    fun updateFilter(query: String) {
        val q = query.trim().lowercase()
        filtered = if (q.isEmpty()) {
            allOptions
        } else {
            allOptions.filter { it.value.lowercase().contains(q) || it.label.lowercase().contains(q) }
        }
        scroll = 0
    }

    private val rowHeight = 14
    private val visibleRows: Int get() = ((h - 8) / rowHeight).coerceAtLeast(1)

    fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (!open) return
        val skin = skinSupplier()
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 300f)
        SkinRenderer.drawSprite(graphics, skin, "panel", x, y, w, h)
        val font = Minecraft.getInstance().font
        val textColor = skin.color("text", 0xFF_EDE4FA.toInt())
        val subColor = skin.color("text_dim", 0xFF_9B8BB4.toInt())

        if (filtered.isEmpty()) {
            graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.no_matches"), x + 8, y + 8, subColor, false)
        } else {
            val end = minOf(filtered.size, scroll + visibleRows)
            for (i in scroll until end) {
                val ry = y + 4 + (i - scroll) * rowHeight
                val hovered = mouseX >= x + 4 && mouseX < x + w - 4 && mouseY >= ry && mouseY < ry + rowHeight
                val itemSprite = if (hovered) "list_item_selected" else "list_item_normal"
                SkinRenderer.drawSprite(graphics, skin, itemSprite, x + 4, ry, w - 8, rowHeight - 1)
                val opt = filtered[i]
                val display = if (opt.label == opt.value) opt.value else "${opt.label} (${opt.value})"
                val trimmed = font.plainSubstrByWidth(display, w - 16)
                graphics.drawString(font, trimmed, x + 7, ry + 3, textColor, false)
            }
        }
        graphics.pose().popPose()
    }

    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!open) return false
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) {
            open = false
            return false
        }
        if (button != 0) return true
        val row = ((mouseY - (y + 4)) / rowHeight).toInt()
        val idx = scroll + row
        if (row >= 0 && idx in filtered.indices) {
            val chosen = filtered[idx]
            open = false
            onPick?.invoke(chosen)
            return true
        }
        return true
    }

    fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        if (!open) return false
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false
        val maxScroll = (filtered.size - visibleRows).coerceAtLeast(0)
        scroll = (scroll - delta.toInt()).coerceIn(0, maxScroll)
        return true
    }
}
