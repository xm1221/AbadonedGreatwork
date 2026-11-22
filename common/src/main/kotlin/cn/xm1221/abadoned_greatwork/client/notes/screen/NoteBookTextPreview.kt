package cn.xm1221.abadoned_greatwork.client.notes.screen

import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import java.text.BreakIterator
import java.util.Locale
import java.util.Optional

/**
 * 与 Patchouli `GuiBook`（`PAGE_WIDTH = 116`、`LINE_HEIGHT = 9`、`Minecraft.UNIFORM_FONT`、`BreakIterator` 分词）
 * 对齐的文本解析与页内预览渲染器，不直接依赖 `vazkii.patchouli.*`。
 */
object NoteBookTextPreview {

    const val BOOK_PAGE_WIDTH = 116
    const val LINE_HEIGHT = 9

    val UNIFORM_FONT_RL: ResourceLocation = ResourceLocation("minecraft", "uniform")
    val BOOK_FONT_STYLE: Style = Style.EMPTY.withFont(UNIFORM_FONT_RL)

    private val COLOR_THING = TextColor.fromRgb(0x8D6ACC)
    private val COLOR_ITEM = TextColor.fromRgb(0xB000B0)
    private val COLOR_ACTION = TextColor.fromRgb(0xFC77BE)
    private val COLOR_MEDIA = TextColor.fromRgb(0x74B3F2)
    private val COLOR_HEX = TextColor.fromRgb(0xB38EF3)
    private val COLOR_LINK = TextColor.fromRgb(0x3A54B4)

    /**
     * 将含 Patchouli / `thehexbook` 宏的源文本解析为带 `minecraft:uniform` 字体的段落式 `Component` 列表。
     */
    fun parseToParagraphs(raw: String, baseColor: Int = 0x2B1F16): List<Component> {
        val normalized = raw.replace("\r\n", "\n")
        if (normalized.isEmpty()) return listOf(Component.empty())

        val paragraphs = mutableListOf<Component>()
        var current: MutableComponent = Component.empty()
        val baseStyle = BOOK_FONT_STYLE.withColor(baseColor and 0x00FFFFFF)
        var style = baseStyle

        fun flushLine() {
            paragraphs.add(current)
            current = Component.empty()
        }

        val buf = StringBuilder()
        fun flushBuf() {
            if (buf.isNotEmpty()) {
                current.append(Component.literal(buf.toString()).withStyle(style))
                buf.setLength(0)
            }
        }

        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            if (ch == '\n') {
                flushBuf()
                flushLine()
                i++
                continue
            }
            if (ch == '/' && i + 1 < normalized.length && normalized[i + 1] == '$') {
                flushBuf()
                style = baseStyle
                i += 2
                continue
            }
            if (ch == '$' && i + 1 < normalized.length && normalized[i + 1] == '(') {
                val close = normalized.indexOf(')', i + 2)
                if (close > 0) {
                    val code = normalized.substring(i + 2, close)
                    val lower = code.lowercase(Locale.ROOT)
                    flushBuf()
                    when {
                        lower == "" || lower == "reset" || lower == "clear" || lower == "/l" || lower == "/t" ->
                            style = baseStyle
                        lower == "br" -> flushLine()
                        lower == "br2" || lower == "2br" || lower == "p" -> {
                            flushLine()
                            paragraphs.add(Component.empty())
                        }
                        lower == "li" || lower == "li2" -> {
                            if (current.string.isNotEmpty()) flushLine()
                            current.append(Component.literal("• ").withStyle(style))
                        }
                        lower == "l" || lower == "bold" -> style = style.withBold(true)
                        lower == "o" || lower == "italic" || lower == "italics" -> style = style.withItalic(true)
                        lower == "n" || lower == "underline" -> style = style.withUnderlined(true)
                        lower == "m" || lower == "strike" -> style = style.withStrikethrough(true)
                        lower == "k" || lower == "obf" -> style = style.withObfuscated(true)
                        lower == "thing" -> style = style.withColor(COLOR_THING)
                        lower == "item" -> style = style.withColor(COLOR_ITEM)
                        lower == "action" -> style = style.withColor(COLOR_ACTION)
                        lower == "media" -> style = style.withColor(COLOR_MEDIA)
                        lower == "hex" -> style = style.withColor(COLOR_HEX)
                        lower.startsWith("l:") -> style = style.withColor(COLOR_LINK).withUnderlined(true)
                        lower.startsWith("k:") -> buf.append("[${code.substring(2)}]")
                        lower.startsWith("#") -> parseHexTextColor(code.substring(1))?.let { style = style.withColor(it) }
                        lower.length == 1 -> ChatFormatting.getByCode(lower[0])?.let { fmt ->
                            style = style.applyFormat(fmt)
                        }
                    }
                    i = close + 1
                    continue
                }
            }
            buf.append(ch)
            i++
        }
        flushBuf()
        paragraphs.add(current)
        return paragraphs
    }

    private fun parseHexTextColor(hex: String): TextColor? = when (hex.length) {
        3 -> {
            val expanded = buildString(6) {
                for (c in hex) {
                    append(c)
                    append(c)
                }
            }
            expanded.toIntOrNull(16)?.let { TextColor.fromRgb(it) }
        }
        6 -> hex.toIntOrNull(16)?.let { TextColor.fromRgb(it) }
        else -> null
    }

    /**
     * 按 Patchouli `TextLayouter` 同款的 `BreakIterator.getLineInstance` 与 `minecraft:uniform` 字符测宽进行折行。
     */
    fun wrapParagraphLikePatchouli(font: Font, paragraph: Component, maxWidth: Int = BOOK_PAGE_WIDTH): List<FormattedCharSequence> {
        val spans = flattenStyledSpans(paragraph)
        val fullText = buildString { spans.forEach { append(it.first) } }
        if (fullText.isEmpty()) return listOf(FormattedCharSequence.EMPTY)

        val charStyles = ArrayList<Style>(fullText.length)
        for ((seg, st) in spans) {
            repeat(seg.length) { charStyles.add(st) }
        }

        val langCode = runCatching { Minecraft.getInstance().languageManager.selected }.getOrNull()
        val locale = if (!langCode.isNullOrBlank()) Locale(langCode) else Locale.getDefault()
        val iterator = BreakIterator.getLineInstance(locale)
        iterator.setText(fullText)

        val out = mutableListOf<FormattedCharSequence>()
        var lineStart = 0
        var width = 0
        var i = 0
        while (i < fullText.length) {
            val ch = fullText[i]
            val cw = font.width(Component.literal(ch.toString()).withStyle(charStyles[i]))
            width += cw
            if (width > maxWidth) {
                var breakOffset = i + 1
                if (!Character.isWhitespace(ch)) {
                    val prec = iterator.preceding(breakOffset)
                    if (prec != BreakIterator.DONE) {
                        breakOffset = prec
                    }
                }
                if (breakOffset <= lineStart) {
                    breakOffset = (i).coerceAtLeast(lineStart + 1)
                }
                out.add(sliceComponent(fullText, charStyles, lineStart, breakOffset).visualOrderText)
                lineStart = breakOffset
                width = 0
                i = lineStart
                continue
            }
            i++
        }
        if (lineStart < fullText.length) {
            out.add(sliceComponent(fullText, charStyles, lineStart, fullText.length).visualOrderText)
        }
        return if (out.isEmpty()) listOf(FormattedCharSequence.EMPTY) else out
    }

    private fun flattenStyledSpans(comp: Component): List<Pair<String, Style>> {
        val out = mutableListOf<Pair<String, Style>>()
        comp.visit(FormattedText.StyledContentConsumer<Unit> { style, str ->
            if (str.isNotEmpty()) out.add(str to style)
            Optional.empty()
        }, BOOK_FONT_STYLE)
        return out
    }

    private fun sliceComponent(text: String, styles: List<Style>, start: Int, end: Int): Component {
        if (start >= end) return Component.empty()
        val root = Component.empty()
        var runStart = start
        var runStyle = styles[start]
        for (idx in (start + 1) until end) {
            if (styles[idx] != runStyle) {
                root.append(Component.literal(text.substring(runStart, idx)).withStyle(runStyle))
                runStart = idx
                runStyle = styles[idx]
            }
        }
        root.append(Component.literal(text.substring(runStart, end)).withStyle(runStyle))
        return root
    }

    private fun wrapAll(font: Font, raw: String, baseColor: Int = 0x2B1F16, maxWidth: Int = BOOK_PAGE_WIDTH): List<FormattedCharSequence> {
        val paras = parseToParagraphs(raw, baseColor)
        val out = mutableListOf<FormattedCharSequence>()
        for (p in paras) {
            if (p.string.isEmpty()) {
                out.add(FormattedCharSequence.EMPTY)
            } else {
                out.addAll(wrapParagraphLikePatchouli(font, p, maxWidth))
            }
        }
        return if (out.isEmpty()) listOf(FormattedCharSequence.EMPTY) else out
    }

    fun countRenderedLines(font: Font, raw: String, maxWidth: Int = BOOK_PAGE_WIDTH): Int {
        if (raw.isEmpty()) return 0
        return wrapAll(font, raw, maxWidth = maxWidth).size
    }

    /**
     * 在编辑器右侧羊皮纸区域以 Patchouli 原生细体（`minecraft:uniform`、`116px` 逻辑列宽、`9px` 行高）渲染真实排版预览。
     */
    fun renderPreview(
        graphics: GuiGraphics,
        font: Font,
        raw: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        scrollLine: Int,
        capacityLines: Int?,
        skin: GuiSkin,
    ): Int {
        SkinRenderer.drawSprite(graphics, skin, "paper_field", x, y, w, h, "normal")

        val baseColor = skin.color("paper_text", 0xFF_2B1F16.toInt())
        val guideColor = skin.color("guide_line", 0x66_B04040)
        val marginCol = 0x22_6B5844

        val lines = wrapAll(font, raw, baseColor, BOOK_PAGE_WIDTH)
        val padding = 6
        val contentOffsetX = ((w - 6 - BOOK_PAGE_WIDTH) / 2).coerceAtLeast(padding)
        val innerH = (h - padding * 2).coerceAtLeast(LINE_HEIGHT)
        val visibleLines = (innerH / LINE_HEIGHT).coerceAtLeast(1)
        val maxScroll = (lines.size - visibleLines).coerceAtLeast(0)
        val clampedScroll = scrollLine.coerceIn(0, maxScroll)

        val left = x + contentOffsetX
        val top = y + padding
        graphics.fill(left - 3, top, left - 2, top + innerH, marginCol)
        graphics.fill(left + BOOK_PAGE_WIDTH + 2, top, left + BOOK_PAGE_WIDTH + 3, top + innerH, marginCol)

        if (capacityLines != null && capacityLines > clampedScroll && capacityLines <= clampedScroll + visibleLines) {
            val gy = top + (capacityLines - clampedScroll) * LINE_HEIGHT - 1
            SkinRenderer.drawDashedHLine(graphics, left, gy, BOOK_PAGE_WIDTH, guideColor)
        }

        val endLine = minOf(lines.size, clampedScroll + visibleLines)
        for (idx in clampedScroll until endLine) {
            val lineY = top + (idx - clampedScroll) * LINE_HEIGHT
            graphics.drawString(font, lines[idx], left, lineY, baseColor, false)
        }

        val renderedCount = if (raw.isEmpty()) 0 else lines.size
        if (capacityLines != null && renderedCount > capacityLines) {
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
            val thumbY = sbY + ((sbH - thumbH) * clampedScroll / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, skin, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }

        return renderedCount
    }
}
