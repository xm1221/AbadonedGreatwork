package cn.xm1221.abadoned_greatwork.client.notes.screen

import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRect
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.notes.NotePatchouliCompat
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteImportScrapC2S
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 笔记残页预览与收录确认界面（贴图驱动）。
 */
class NoteScrapPreviewScreen(
    private val entry: NoteEntry,
    private val offhand: Boolean,
) : Screen(Component.translatable("abadoned_greatwork.notes.scrap_preview_title")) {

    private val skin: GuiSkin get() = GuiSkinLoader.get("note_preview")
    private var winX: Int = 0
    private var winY: Int = 0
    private var scroll: Int = 0

    override fun init() {
        val s = skin
        winX = ((width - s.layoutWidth) / 2).coerceAtLeast(0)
        winY = ((height - s.layoutHeight) / 2).coerceAtLeast(0)

        val footer = s.region("footer_bar", SkinRect(10, 186, 256, 20))
        val fx = winX + footer.x
        val fy = winY + footer.y + 2

        addRenderableWidget(
            SkinButton({ skin }, fx, fy, 88, 16, Component.translatable("abadoned_greatwork.notes.btn.preview"), "button", "icon.eye") {
                NotePatchouliCompat.openDraftPreview(entry, this, 0)
            }.apply { active = NotePatchouliCompat.isAvailable() }
        )
        addRenderableWidget(
            SkinButton({ skin }, fx + 92, fy, 102, 16, Component.translatable("abadoned_greatwork.notes.btn.import_scrap"), "button_accent", "icon.import") {
                MsgNoteImportScrapC2S(offhand).sendToServer()
                onClose()
            }
        )
        addRenderableWidget(
            SkinButton({ skin }, fx + footer.w - 56, fy, 56, 16, Component.translatable("abadoned_greatwork.notes.btn.close"), "button") {
                onClose()
            }
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(graphics)
        val s = skin
        SkinRenderer.drawSprite(graphics, s, "panel", winX, winY, s.layoutWidth, s.layoutHeight)

        val header = s.region("header", SkinRect(10, 10, 256, 26))
        val listRect = s.region("page_list", SkinRect(10, 40, 256, 140))

        val hx = winX + header.x
        val hy = winY + header.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", hx, hy, header.w, header.h)
        graphics.renderItem(NoteLibraryScreen.resolveIconStack(entry.icon), hx + 5, hy + 5)

        val entryTitle = entry.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
        graphics.drawString(font, font.plainSubstrByWidth(entryTitle, header.w - 34), hx + 26, hy + 4, s.color("title", 0xFF_F2E6FF.toInt()), false)
        val meta = Component.translatable("abadoned_greatwork.notes.scrap_author", entry.author.ifBlank { "?" }, entry.pages.size)
        graphics.drawString(font, meta, hx + 26, hy + 14, s.color("text_dim", 0xFF_9E8EB8.toInt()), false)

        val lx = winX + listRect.x
        val ly = winY + listRect.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", lx, ly, listRect.w, listRect.h)

        val rowH = 22
        val visibleRows = ((listRect.h - 8) / rowH).coerceAtLeast(1)
        val maxScroll = (entry.pages.size - visibleRows).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, maxScroll)

        val end = minOf(entry.pages.size, scroll + visibleRows)
        for (i in scroll until end) {
            val ry = ly + 4 + (i - scroll) * rowH
            val rowW = listRect.w - (if (entry.pages.size > visibleRows) 14 else 8)
            SkinRenderer.drawSprite(graphics, s, "list_item_normal", lx + 4, ry, rowW, rowH - 2)
            val page = entry.pages[i]
            SkinRenderer.drawSprite(graphics, s, "icon.page.${NoteLibraryScreen.pageIconSuffix(page.type)}", lx + 7, ry + 4, 12, 12)
            val typeName = Component.translatable(page.type.langKey).string
            val detail = page.title.ifBlank { page.field("item").ifBlank { page.field("op_id").ifBlank { page.field("recipe") } } }
            val line1 = if (detail.isNotBlank()) "#${i + 1} $typeName — $detail" else "#${i + 1} $typeName"
            graphics.drawString(font, font.plainSubstrByWidth(line1, rowW - 26), lx + 22, ry + 2, s.color("text", 0xFF_EDE4FA.toInt()), false)
            val snippet = page.text.replace('\n', ' ').trim()
            if (snippet.isNotEmpty()) {
                graphics.drawString(font, font.plainSubstrByWidth(snippet, rowW - 26), lx + 22, ry + 11, s.color("text_dim", 0xFF_9E8EB8.toInt()), false)
            }
        }

        if (entry.pages.size > visibleRows) {
            val sbX = lx + listRect.w - 8
            val sbY = ly + 4
            val sbH = listRect.h - 8
            SkinRenderer.drawSprite(graphics, s, "scrollbar_track", sbX, sbY, 4, sbH)
            val thumbH = maxOf(10, sbH * visibleRows / entry.pages.size)
            val thumbY = sbY + ((sbH - thumbH) * scroll / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, s, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }

        super.render(graphics, mouseX, mouseY, partialTicks)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        val listRect = skin.region("page_list", SkinRect(10, 40, 256, 140))
        val lx = winX + listRect.x
        val ly = winY + listRect.y
        if (mouseX >= lx && mouseX < lx + listRect.w && mouseY >= ly && mouseY < ly + listRect.h) {
            val rowH = 22
            val visibleRows = ((listRect.h - 8) / rowH).coerceAtLeast(1)
            val maxScroll = (entry.pages.size - visibleRows).coerceAtLeast(0)
            scroll = (scroll - delta.toInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, delta)
    }
}
