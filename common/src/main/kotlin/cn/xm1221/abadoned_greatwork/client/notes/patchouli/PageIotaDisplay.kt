package cn.xm1221.abadoned_greatwork.client.notes.patchouli

import cn.xm1221.abadoned_greatwork.api.iotaColor
import cn.xm1221.abadoned_greatwork.api.iotaDisplay
import com.google.gson.JsonElement
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.locale.Language
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import vazkii.patchouli.client.book.BookContentsBuilder
import vazkii.patchouli.client.book.BookEntry
import vazkii.patchouli.client.book.gui.GuiBook
import vazkii.patchouli.client.book.page.abstr.PageWithText

/**
 * 自定义 Patchouli 书页类型（`abadoned_greatwork:iota_display`）。
 * 严格遵循客户端 tag-only 契约：仅通过 `HexPort` tag 解码读取颜色与展示文本，
 * 不在客户端进行任何实体/世界状态操作。
 */
class PageIotaDisplay : PageWithText() {

    var title: String = ""

    @Transient
    private var parsedIotas: List<CompoundTag> = emptyList()

    @Transient
    private var displayLines: List<Pair<Int, Component>> = emptyList()

    override fun build(level: Level?, entry: BookEntry, builder: BookContentsBuilder, pageNum: Int) {
        super.build(level, entry, builder, pageNum)
        val rawTitle = sourceObject?.get("title")?.asString.orEmpty()
        if (title.isEmpty()) title = rawTitle

        val list = mutableListOf<CompoundTag>()
        val arr = sourceObject?.getAsJsonArray("iotas")
        if (arr != null) {
            for (el in arr) {
                parseTagElement(el)?.let { list.add(it) }
            }
        }
        parsedIotas = list
        val registries = level?.registryAccess()
        displayLines = list.map { tag ->
            val color = runCatching { iotaColor(tag, registries) }.getOrDefault(0xAAAAAA)
            val comp = runCatching { iotaDisplay(tag, registries) }
                .getOrElse { Component.translatable("hexcasting.spelldata.unknown") }
            color to comp
        }
    }

    override fun getTextHeight(): Int = 86

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, pticks: Float) {
        val headerText = if (title.isNotBlank()) i18n(title) else Component.translatable("abadoned_greatwork.notes.page_type.iota_display").string
        parent.drawCenteredStringNoShadow(graphics, headerText, GuiBook.PAGE_WIDTH / 2, 0, book.headerColor)
        GuiBook.drawSeparator(graphics, book, 0, 12)

        val boxX = 0
        val boxY = 18
        val boxW = GuiBook.PAGE_WIDTH
        val boxH = 62
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0x33_2A1F38)
        graphics.renderOutline(boxX, boxY, boxW, boxH, 0x66_7B58A8)

        if (displayLines.isEmpty()) {
            val emptyMsg = Component.translatable("abadoned_greatwork.notes.iota_empty")
            graphics.drawString(fontRenderer, emptyMsg, boxX + 4, boxY + 6, 0x777777, false)
        } else {
            val maxRows = 5
            val rowH = 11
            val visibleCount = minOf(displayLines.size, maxRows)
            for (i in 0 until visibleCount) {
                val (color, comp) = displayLines[i]
                val ry = boxY + 4 + i * rowH
                val swatchColor = color or (0xFF shl 24)
                graphics.fill(boxX + 4, ry + 2, boxX + 8, ry + 6, swatchColor)
                val trimmed = Language.getInstance().getVisualOrder(fontRenderer.substrByWidth(comp, boxW - 14))
                graphics.drawString(fontRenderer, trimmed, boxX + 11, ry, 0xFF2B2233.toInt(), false)

                if (mouseX >= boxX && mouseX < boxX + boxW && mouseY >= ry && mouseY < ry + rowH) {
                    parent.setTooltip(comp)
                }
            }
            if (displayLines.size > maxRows) {
                val more = Component.translatable("abadoned_greatwork.notes.more_count", displayLines.size - maxRows)
                graphics.drawString(fontRenderer, more, boxX + 4, boxY + boxH - 10, 0xFF666666.toInt(), false)
            }
        }

        super.render(graphics, mouseX, mouseY, pticks)
    }

    private fun parseTagElement(el: JsonElement): CompoundTag? = runCatching {
        val snbt = if (el.isJsonPrimitive) el.asString else el.toString()
        TagParser.parseTag(snbt)
    }.getOrNull()
}
