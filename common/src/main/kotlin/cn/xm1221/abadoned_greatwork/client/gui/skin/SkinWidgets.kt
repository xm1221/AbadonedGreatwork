package cn.xm1221.abadoned_greatwork.client.gui.skin

import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteBookTextPreview
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.font.TextFieldHelper
import net.minecraft.client.gui.screens.Screen
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW

/**
 * 贴图驱动的多态按钮（支持 normal/hover/disabled/selected、文字裁剪与暗底/羊皮纸双色板）。
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
    private val textColorKey: String = if (spriteKey == "paper_button") "paper_button_text" else "button_text",
    private val disabledColorKey: String = if (spriteKey == "paper_button") "paper_button_text_disabled" else "button_text_disabled",
    onPress: OnPress,
) : Button(x, y, w, h, msg, onPress, DEFAULT_NARRATION) {

    var selected: Boolean = false

    init {
        if (tooltipText != null) {
            tooltip = Tooltip.create(tooltipText)
        }
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val skin = skinSupplier()
        val state = when {
            !active -> "disabled"
            selected -> "selected"
            isHoveredOrFocused -> "hover"
            else -> "normal"
        }
        SkinRenderer.drawSprite(graphics, skin, spriteKey, x, y, width, height, state)

        val font = Minecraft.getInstance().font
        val textColor = if (active) {
            skin.color(textColorKey, if (spriteKey == "paper_button") 0xFF_2B1F16.toInt() else 0xFF_F4EEFC.toInt())
        } else {
            skin.color(disabledColorKey, if (spriteKey == "paper_button") 0xFF_8C7B65.toInt() else 0xFF_7A7088.toInt())
        }

        val label = message
        val hasLabel = label.string.isNotEmpty()
        if (iconKey != null && !hasLabel) {
            val ix = x + (width - 12) / 2
            val iy = y + (height - 12) / 2
            SkinRenderer.drawSprite(graphics, skin, iconKey, ix, iy, 12, 12)
        } else if (iconKey != null) {
            val availTextW = (width - 20).coerceAtLeast(8)
            val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(label, availTextW))
            val textW = font.width(trimmed)
            val totalW = 12 + 3 + textW
            val startX = x + ((width - totalW) / 2).coerceAtLeast(3)
            SkinRenderer.drawSprite(graphics, skin, iconKey, startX, y + (height - 12) / 2, 12, 12)
            graphics.drawString(font, trimmed, startX + 15, y + (height - 8) / 2, textColor, false)
        } else {
            val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(label, (width - 6).coerceAtLeast(8)))
            SkinRenderer.drawCenteredNoShadow(graphics, font, trimmed, x + width / 2, y + (height - 8) / 2, textColor)
        }
    }
}

/**
 * 贴图驱动的单行输入框（支持 normal / focused 双态精灵，羊皮纸底色采用无阴影清晰渲染）。
 */
class SkinTextField(
    private val skinSupplier: () -> GuiSkin,
    private val textFont: Font,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    label: Component,
    private val spriteKey: String = "dark_field",
    private val textColorKey: String = if (spriteKey == "paper_field") "paper_text" else "text",
) : EditBox(textFont, x + 4, y + (h - 8) / 2, (w - 8).coerceAtLeast(8), h, label) {

    private val boxX = x
    private val boxY = y
    private val boxW = w
    private val boxH = h
    private var cachedHint: Component? = null
    private var trackedDisplayPos: Int = 0

    init {
        setBordered(false)
    }

    override fun setHint(hint: Component) {
        cachedHint = hint
        super.setHint(hint)
    }

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (!isVisible) return
        val skin = skinSupplier()
        val state = if (isFocused) "focused" else "normal"
        SkinRenderer.drawSprite(graphics, skin, spriteKey, boxX, boxY, boxW, boxH, state)
        val col = skin.color(textColorKey, if (spriteKey == "paper_field") 0xFF_2B1F16.toInt() else 0xFF_F2EBF9.toInt())
        val dimCol = skin.color(if (spriteKey == "paper_field") "paper_text_dim" else "text_dim", 0xFF_8D82A0.toInt())

        if (spriteKey != "paper_field") {
            setTextColor(col)
            setTextColorUneditable(dimCol)
            super.renderWidget(graphics, mouseX, mouseY, partialTicks)
            return
        }

        val textVal = value
        val cur = cursorPosition.coerceIn(0, textVal.length)
        val availW = innerWidth.coerceAtLeast(8)
        trackedDisplayPos = trackedDisplayPos.coerceIn(0, textVal.length)
        if (cur < trackedDisplayPos) {
            trackedDisplayPos = cur
        } else {
            val visNow = textFont.plainSubstrByWidth(textVal.substring(trackedDisplayPos), availW)
            if (cur > trackedDisplayPos + visNow.length) {
                val tail = textFont.plainSubstrByWidth(textVal.substring(0, cur), availW, true)
                trackedDisplayPos = (cur - tail.length).coerceAtLeast(0)
            }
        }

        val shown = textFont.plainSubstrByWidth(textVal.substring(trackedDisplayPos), availW)
        val textX = boxX + 4
        val textY = boxY + (boxH - 8) / 2

        val hl = highlighted
        if (hl.isNotEmpty()) {
            val (selMin, selMax) = if (cur >= hl.length && textVal.substring(cur - hl.length, cur) == hl) {
                (cur - hl.length) to cur
            } else {
                cur to (cur + hl.length).coerceAtMost(textVal.length)
            }
            val vs = (selMin - trackedDisplayPos).coerceIn(0, shown.length)
            val ve = (selMax - trackedDisplayPos).coerceIn(0, shown.length)
            if (ve > vs) {
                val sx = textFont.width(shown.substring(0, vs))
                val ex = textFont.width(shown.substring(0, ve))
                graphics.fill(textX + sx, textY - 1, textX + ex, textY + 9, skin.color("selection", 0x44_8E44AD.toInt()))
            }
        }

        if (textVal.isNotEmpty()) {
            graphics.drawString(textFont, shown, textX, textY, col, false)
        } else {
            val hintComp = cachedHint
            if (hintComp != null) {
                val trimmedHint = Language.getInstance().getVisualOrder(textFont.substrByWidth(hintComp, availW))
                graphics.drawString(textFont, trimmedHint, textX, textY, dimCol, false)
            }
        }

        if (isFocused && ((System.currentTimeMillis() / 500L) % 2L == 0L)) {
            val visCur = (cur - trackedDisplayPos).coerceIn(0, shown.length)
            val caretX = textX + textFont.width(shown.substring(0, visCur))
            graphics.fill(caretX, textY - 1, caretX + 1, textY + 9, skin.color("caret", 0xFF_6B2FA0.toInt()))
        }
    }
}

/**
 * 贴图驱动的多行书页正文编辑器：
 * - 逻辑行宽固定 `116`（与 Patchouli `GuiBook.PAGE_WIDTH` 一致）
 * - 支持 `↑/↓` 跨视觉行移动（记忆目标 X 像素列）、`Home/End`、`Ctrl+Home/End`
 * - 支持鼠标点击定位、双击选词、拖拽选区与可拖动滚动条
 * - 支持选中文本包裹格式码与当前书页类型容量参考底线
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
    private val paperSprite: String? = null,
    private val textColorKey: String = "paper_text",
    private val defaultTextColor: Int = 0xFF_2B1F16.toInt(),
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
    var capacityLines: Int? = null
    private var scrollLine: Int = 0
    private val padding = 6
    private var preferredCaretX: Int? = null
    private var draggingText: Boolean = false
    private var dragAnchorPos: Int = 0
    private var draggingScrollbar: Boolean = false
    private var scrollbarDragOffsetY: Int = 0
    private var lastClickTime: Long = 0L
    private var lastClickPos: Int = -1

    private val contentOffsetX: Int get() = ((w - 6 - PAGE_LOGICAL_WIDTH) / 2).coerceAtLeast(padding)
    private val innerH: Int get() = (h - padding * 2).coerceAtLeast(18)
    private val visibleLines: Int get() = (innerH / LINE_HEIGHT).coerceAtLeast(1)

    fun wrapSelectionOrInsert(openTag: String, closeTag: String) {
        val text = getter()
        val cursor = helper.getCursorPos().coerceIn(0, text.length)
        val sel = helper.getSelectionPos().coerceIn(0, text.length)
        val min = minOf(cursor, sel)
        val max = maxOf(cursor, sel)
        if (min < max) {
            val selected = text.substring(min, max)
            val replaced = openTag + selected + closeTag
            helper.insertText(replaced)
            val endPos = (min + replaced.length).coerceAtMost(getter().length)
            helper.setCursorPos(endPos)
            helper.setSelectionPos(endPos)
        } else {
            helper.insertText(openTag + closeTag)
            val midPos = (min + openTag.length).coerceAtMost(getter().length)
            helper.setCursorPos(midPos)
            helper.setSelectionPos(midPos)
        }
        preferredCaretX = null
        ensureCaretVisible()
    }

    fun insertToken(token: String) {
        helper.insertText(token)
        preferredCaretX = null
        ensureCaretVisible()
    }

    fun resetCursor() {
        helper.setCursorPos(0)
        helper.setSelectionPos(0)
        scrollLine = 0
        preferredCaretX = null
    }

    fun isMouseOver(mx: Double, my: Double): Boolean =
        mx >= x && mx < x + w && my >= y && my < y + h

    private val bookStyle: Style get() = NoteBookTextPreview.BOOK_FONT_STYLE

    private fun widthUniform(s: String): Int =
        if (s.isEmpty()) 0 else font.width(Component.literal(s).withStyle(bookStyle))

    private fun screenToTextOffset(mx: Double, my: Double): Int {
        val lx = (mx - x - contentOffsetX).toInt().coerceAtLeast(0)
        val ly = (my - y - padding).toInt().coerceAtLeast(0)
        val visRow = ly / LINE_HEIGHT
        val targetRow = (scrollLine + visRow).coerceAtLeast(0)
        val text = getter()
        val lines = wrapLines(text)
        val clampedRow = targetRow.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val lineText = lines.getOrNull(clampedRow) ?: ""
        val col = font.splitter.plainIndexAtWidth(lineText, lx, bookStyle)
        return (rowToTextOffset(text, clampedRow) + col).coerceIn(0, text.length)
    }

    fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        if (button != 0) return false
        if (!isMouseOver(mx, my)) {
            focused = false
            draggingText = false
            draggingScrollbar = false
            return false
        }
        focused = true
        preferredCaretX = null

        val lines = wrapLines(getter())
        val sbX = x + w - 7
        val sbY = y + 4
        val sbH = (h - 8).coerceAtLeast(16)
        if (lines.size > visibleLines && mx >= sbX - 1 && mx <= sbX + 6 && my >= sbY && my <= sbY + sbH) {
            val maxScroll = (lines.size - visibleLines).coerceAtLeast(1)
            val thumbH = maxOf(10, sbH * visibleLines / lines.size)
            val thumbY = sbY + ((sbH - thumbH) * scrollLine / maxScroll)
            if (my >= thumbY && my <= thumbY + thumbH) {
                draggingScrollbar = true
                scrollbarDragOffsetY = (my - thumbY).toInt()
            } else {
                val frac = ((my - sbY - thumbH / 2.0) / (sbH - thumbH).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                scrollLine = (frac * maxScroll).toInt().coerceIn(0, maxScroll)
                draggingScrollbar = true
                scrollbarDragOffsetY = thumbH / 2
            }
            return true
        }

        val pos = screenToTextOffset(mx, my)
        val now = System.currentTimeMillis()
        if (pos == lastClickPos && now - lastClickTime < 320L) {
            selectWordAt(pos)
            draggingText = false
        } else {
            dragAnchorPos = pos
            helper.setCursorPos(pos)
            helper.setSelectionPos(pos)
            draggingText = true
        }
        lastClickPos = pos
        lastClickTime = now
        return true
    }

    fun mouseDragged(mx: Double, my: Double, button: Int): Boolean {
        if (button != 0) return false
        if (draggingScrollbar) {
            val lines = wrapLines(getter())
            val maxScroll = (lines.size - visibleLines).coerceAtLeast(1)
            val sbY = y + 4
            val sbH = (h - 8).coerceAtLeast(16)
            val thumbH = maxOf(10, sbH * visibleLines / lines.size)
            val span = (sbH - thumbH).coerceAtLeast(1)
            val frac = ((my - sbY - scrollbarDragOffsetY) / span.toDouble()).coerceIn(0.0, 1.0)
            scrollLine = (frac * maxScroll).toInt().coerceIn(0, maxScroll)
            return true
        }
        if (draggingText) {
            val pos = screenToTextOffset(mx, my)
            helper.setCursorPos(pos)
            helper.setSelectionPos(dragAnchorPos)
            ensureCaretVisible()
            return true
        }
        return false
    }

    fun mouseReleased(button: Int): Boolean {
        if (button != 0) return false
        val wasDragging = draggingText || draggingScrollbar
        draggingText = false
        draggingScrollbar = false
        return wasDragging
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
        val shift = Screen.hasShiftDown()
        val ctrl = Screen.hasControlDown()

        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                helper.insertText("\n")
                preferredCaretX = null
                ensureCaretVisible()
                return true
            }
            GLFW.GLFW_KEY_UP -> {
                moveCaretVertically(-1, shift)
                return true
            }
            GLFW.GLFW_KEY_DOWN -> {
                moveCaretVertically(1, shift)
                return true
            }
            GLFW.GLFW_KEY_HOME -> {
                moveToLineEdge(start = true, document = ctrl, keepSelection = shift)
                return true
            }
            GLFW.GLFW_KEY_END -> {
                moveToLineEdge(start = false, document = ctrl, keepSelection = shift)
                return true
            }
        }
        preferredCaretX = null
        val handled = helper.keyPressed(keyCode)
        if (handled) ensureCaretVisible()
        return handled
    }

    fun charTyped(ch: Char, modifiers: Int): Boolean {
        if (!focused) return false
        preferredCaretX = null
        if (ch == '\n' || ch == '\r') {
            helper.insertText("\n")
            ensureCaretVisible()
            return true
        }
        val handled = helper.charTyped(ch)
        if (handled) ensureCaretVisible()
        return handled
    }

    private fun selectWordAt(pos: Int) {
        val text = getter()
        if (text.isEmpty()) return
        val idx = pos.coerceIn(0, text.lastIndex)
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
        if (!isWordChar(text[idx])) {
            helper.setCursorPos((idx + 1).coerceAtMost(text.length))
            helper.setSelectionPos(idx)
            return
        }
        var l = idx
        while (l > 0 && isWordChar(text[l - 1])) l--
        var r = idx + 1
        while (r < text.length && isWordChar(text[r])) r++
        helper.setCursorPos(r)
        helper.setSelectionPos(l)
    }

    private fun moveCaretVertically(deltaRow: Int, keepSelection: Boolean) {
        val text = getter()
        val anchor = helper.getSelectionPos().coerceIn(0, text.length)
        val cursor = helper.getCursorPos().coerceIn(0, text.length)
        val lines = wrapLines(text)
        val starts = lineStarts(text)
        val (row, col) = cursorToRowCol(text, cursor)
        val curLine = lines.getOrNull(row) ?: ""
        val targetX = preferredCaretX ?: widthUniform(curLine.substring(0, col.coerceIn(0, curLine.length))).also {
            preferredCaretX = it
        }
        val nextRow = (row + deltaRow).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val nextLine = lines.getOrNull(nextRow) ?: ""
        val nextCol = font.splitter.plainIndexAtWidth(nextLine, targetX, bookStyle)
        val nextPos = (starts.getOrElse(nextRow) { text.length } + nextCol).coerceIn(0, text.length)
        helper.setCursorPos(nextPos)
        helper.setSelectionPos(if (keepSelection) anchor else nextPos)
        ensureCaretVisible()
    }

    private fun moveToLineEdge(start: Boolean, document: Boolean, keepSelection: Boolean) {
        val text = getter()
        val anchor = helper.getSelectionPos().coerceIn(0, text.length)
        val cursor = helper.getCursorPos().coerceIn(0, text.length)
        val nextPos = if (document) {
            if (start) 0 else text.length
        } else {
            val lines = wrapLines(text)
            val starts = lineStarts(text)
            val (row, _) = cursorToRowCol(text, cursor)
            val lineStart = starts.getOrElse(row) { text.length }
            val lineLen = lines.getOrNull(row)?.length ?: 0
            if (start) lineStart else (lineStart + lineLen).coerceIn(0, text.length)
        }
        preferredCaretX = null
        helper.setCursorPos(nextPos)
        helper.setSelectionPos(if (keepSelection) anchor else nextPos)
        ensureCaretVisible()
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
        if (paperSprite != null) {
            SkinRenderer.drawSprite(graphics, skin, paperSprite, x, y, w, h)
        } else {
            val fieldState = if (focused) "focused" else "normal"
            SkinRenderer.drawSprite(graphics, skin, "paper_field", x, y, w, h, fieldState)
        }

        val text = getter()
        val lines = wrapLines(text)
        val maxScroll = (lines.size - visibleLines).coerceAtLeast(0)
        scrollLine = scrollLine.coerceIn(0, maxScroll)

        val textColor = skin.color(textColorKey, defaultTextColor)
        val caretColor = skin.color("caret", 0xFF_6B2FA0.toInt())
        val selColor = skin.color("selection", 0x44_8E44AD)
        val guideColor = skin.color("guide_line", 0x66_B04040)

        val cursor = helper.getCursorPos().coerceIn(0, text.length)
        val sel = helper.getSelectionPos().coerceIn(0, text.length)
        val selMin = minOf(cursor, sel)
        val selMax = maxOf(cursor, sel)
        val starts = lineStarts(text)

        val left = x + contentOffsetX
        val top = y + padding
        graphics.fill(left - 3, top, left - 2, top + innerH, 0x22_6B5844)
        graphics.fill(left + PAGE_LOGICAL_WIDTH + 2, top, left + PAGE_LOGICAL_WIDTH + 3, top + innerH, 0x22_6B5844)

        graphics.pose().pushPose()
        graphics.pose().translate(left.toFloat(), top.toFloat(), 0f)

        val cap = capacityLines
        if (cap != null && cap > scrollLine && cap <= scrollLine + visibleLines) {
            val gy = (cap - scrollLine) * LINE_HEIGHT - 1
            SkinRenderer.drawDashedHLine(graphics, 0, gy, PAGE_LOGICAL_WIDTH, guideColor)
        }

        val endLine = minOf(lines.size, scrollLine + visibleLines)
        for (i in scrollLine until endLine) {
            val visY = (i - scrollLine) * LINE_HEIGHT
            val lineStr = lines[i]
            val lineStart = starts.getOrElse(i) { text.length }
            val lineEnd = lineStart + lineStr.length

            if (selMin < selMax && selMax > lineStart && selMin <= lineEnd) {
                val sCol = (selMin - lineStart).coerceIn(0, lineStr.length)
                val eCol = (selMax - lineStart).coerceIn(0, lineStr.length)
                val sx = widthUniform(lineStr.substring(0, sCol))
                val ex = widthUniform(lineStr.substring(0, eCol))
                if (ex > sx) {
                    graphics.fill(sx, visY, ex, visY + LINE_HEIGHT, selColor)
                }
            }

            graphics.drawString(font, Component.literal(lineStr).withStyle(bookStyle), 0, visY, textColor, false)
        }

        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            val (row, col) = cursorToRowCol(text, cursor)
            val visRow = row - scrollLine
            if (visRow in 0 until visibleLines) {
                val lineText = lines.getOrNull(row) ?: ""
                val caretX = widthUniform(lineText.substring(0, col.coerceIn(0, lineText.length)))
                val caretY = visRow * LINE_HEIGHT
                graphics.fill(caretX, caretY, caretX + 1, caretY + LINE_HEIGHT, caretColor)
            }
        }
        graphics.pose().popPose()

        val renderedCount = lineCount()
        if (cap != null && renderedCount > cap) {
            val warn = Component.translatable("abadoned_greatwork.notes.page_overflow")
            val ww = font.width(warn)
            graphics.drawString(font, warn, x + w - ww - 9, y + h - 10, skin.color("error", 0xFF_EC6270.toInt()), false)
        }

        if (lines.size > visibleLines) {
            val sbX = x + w - 7
            val sbY = y + 4
            val sbH = (h - 8).coerceAtLeast(16)
            SkinRenderer.drawSprite(graphics, skin, "scrollbar_track", sbX, sbY, 4, sbH)
            val thumbH = maxOf(10, sbH * visibleLines / lines.size)
            val thumbY = sbY + ((sbH - thumbH) * scrollLine / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, skin, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }
    }

    fun lineCount(): Int = NoteBookTextPreview.countRenderedLines(font, getter(), PAGE_LOGICAL_WIDTH)

    private fun wrapLines(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val splitter = font.splitter
        val out = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val nl = remaining.indexOf('\n')
            val limit = splitter.plainIndexAtWidth(remaining, PAGE_LOGICAL_WIDTH, bookStyle).coerceAtLeast(1)
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
            val limit = splitter.plainIndexAtWidth(remaining, PAGE_LOGICAL_WIDTH, bookStyle).coerceAtLeast(1)
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
