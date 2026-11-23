package cn.xm1221.abadoned_greatwork.client.notes.screen

import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRect
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteImportScrapC2S
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NotePage
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * 笔记碎片预览与导入界面：
 * 点击左侧目录中的页条目在右侧羊皮纸卡片上查看可读内容，点击导入等待服务端 `MsgNoteResultS2C` 后确认完成。
 */
class NoteScrapPreviewScreen(
    private val entry: NoteEntry,
    private val offhand: Boolean,
) : Screen(Component.translatable("abadoned_greatwork.notes.scrap.preview_title")) {

    private var guiLeft: Int = 0
    private var guiTop: Int = 0
    private var guiW: Int = 316
    private var guiH: Int = 220

    private var selectedPageIdx: Int = 0
    private var pageScrollOffset: Int = 0

    private lateinit var importBtn: SkinButton
    private lateinit var closeBtn: SkinButton

    private var awaitingImport: Boolean = false
    private var errorMsg: Component? = null

    private val skin: GuiSkin get() = GuiSkinLoader.get("note_preview")

    private val resultListener: (Boolean, String, List<String>) -> Unit = { success, messageKey, args ->
        Minecraft.getInstance().execute {
            if (!awaitingImport) return@execute
            awaitingImport = false
            importBtn.active = true
            importBtn.message = Component.translatable("abadoned_greatwork.notes.btn.import_scrap")
            if (success) {
                minecraft?.setScreen(NoteLibraryScreen())
            } else {
                errorMsg = Component.translatable(messageKey, *args.toTypedArray())
            }
        }
    }

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        val s = skin
        guiW = s.layoutWidth
        guiH = s.layoutHeight
        guiLeft = (width - guiW) / 2
        guiTop = (height - guiH) / 2

        clearWidgets()
        ClientNoteLibrary.removeResultListener(resultListener)
        ClientNoteLibrary.addResultListener(resultListener)

        val footer = r("footer_bar", 10, 194, 296, 18)
        val fx = guiLeft + footer.x
        val fy = guiTop + footer.y + 1

        closeBtn = addRenderableWidget(
            SkinButton({ skin }, fx + footer.w - 176, fy, 64, 16, Component.translatable("abadoned_greatwork.notes.btn.close"), "button") {
                onClose()
            }
        )
        importBtn = addRenderableWidget(
            SkinButton({ skin }, fx + footer.w - 108, fy, 108, 16, Component.translatable("abadoned_greatwork.notes.btn.import_scrap"), "button_accent", "icon.import") {
                if (awaitingImport) return@SkinButton
                awaitingImport = true
                errorMsg = null
                importBtn.active = false
                importBtn.message = Component.translatable("abadoned_greatwork.notes.btn.saving")
                MsgNoteImportScrapC2S(offhand).sendToServer()
            }
        )
    }

    override fun removed() {
        ClientNoteLibrary.removeResultListener(resultListener)
        NoteVisualHelpers.clearEntityCache()
        super.removed()
    }

    private fun r(key: String, dx: Int, dy: Int, dw: Int, dh: Int): SkinRect =
        skin.region(key, SkinRect(dx, dy, dw, dh))

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Rendered explicitly at the start of render() so super.render() does not blur twice or draw over the panel
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.renderBackground(graphics, mouseX, mouseY, partialTicks)
        val s = skin

        SkinRenderer.drawSprite(graphics, s, "panel", guiLeft, guiTop, guiW, guiH)

        renderHeader(graphics, s)
        renderPageList(graphics, s, mouseX, mouseY)
        renderSnippetPaper(graphics, s)

        errorMsg?.let { err ->
            val footer = r("footer_bar", 10, 194, 296, 18)
            val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(err, 112))
            graphics.drawString(font, trimmed, guiLeft + footer.x, guiTop + footer.y + 5, s.color("error", 0xFF_EC6270.toInt()), false)
        }

        super.render(graphics, mouseX, mouseY, partialTicks)
    }

    private fun renderHeader(graphics: GuiGraphics, s: GuiSkin) {
        val hdr = r("header", 10, 8, 296, 26)
        val hx = guiLeft + hdr.x
        val hy = guiTop + hdr.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", hx, hy, hdr.w, hdr.h)

        SkinRenderer.drawSprite(graphics, s, "dark_slot", hx + 3, hy + 2, 22, 22)
        graphics.renderItem(NoteVisualHelpers.resolveItemStack(entry.icon), hx + 6, hy + 5)

        val accent = GuiSkin.parseHexColor("FF${entry.color}") ?: 0xFF_9B59B6.toInt()
        graphics.fill(hx + 28, hy + 4, hx + 31, hy + 15, accent)

        val titleStr = entry.title.ifBlank { I18n.get("abadoned_greatwork.notes.untitled") }
        graphics.drawString(font, font.plainSubstrByWidth(titleStr, hdr.w - 40), hx + 34, hy + 4, s.color("title", 0xFF_F2EBF9.toInt()), false)

        val sub = Component.translatable("abadoned_greatwork.notes.scrap.meta", entry.author.ifBlank { "?" }, entry.pages.size)
        val subTrimmed = Language.getInstance().getVisualOrder(font.substrByWidth(sub, hdr.w - 40))
        graphics.drawString(font, subTrimmed, hx + 28, hy + 15, s.color("text_dim", 0xFF_A293B8.toInt()), false)
    }

    private fun renderPageList(graphics: GuiGraphics, s: GuiSkin, mouseX: Int, mouseY: Int) {
        val listRect = r("page_list", 10, 38, 114, 150)
        val lx = guiLeft + listRect.x
        val ly = guiTop + listRect.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", lx, ly, listRect.w, listRect.h)

        val pages = entry.pages
        if (pages.isEmpty()) return
        val maxScroll = (pages.size - VISIBLE_PAGE_ROWS).coerceAtLeast(0)
        pageScrollOffset = pageScrollOffset.coerceIn(0, maxScroll)

        val rowW = if (pages.size > VISIBLE_PAGE_ROWS) listRect.w - 13 else listRect.w - 8
        val end = minOf(pages.size, pageScrollOffset + VISIBLE_PAGE_ROWS)
        for (idx in pageScrollOffset until end) {
            val rel = idx - pageScrollOffset
            val rx = lx + 4
            val ry = ly + 4 + rel * PAGE_ROW_H
            val rowH = PAGE_ROW_H - 1
            val selected = idx == selectedPageIdx
            val hovered = mouseX >= rx && mouseX < rx + rowW && mouseY >= ry && mouseY < ry + rowH
            SkinRenderer.drawSprite(graphics, s, if (selected || hovered) "list_item_selected" else "list_item_normal", rx, ry, rowW, rowH)

            val p = pages[idx]
            SkinRenderer.drawSprite(graphics, s, "icon.page.${NoteVisualHelpers.pageIconSuffix(p.type)}", rx + 3, ry + 2, 12, 12)
            val label = if (p.title.isNotBlank()) "${idx + 1}. ${p.title}" else "${idx + 1}. ${I18n.get(p.type.langKey)}"
            graphics.drawString(font, font.plainSubstrByWidth(label, rowW - 20), rx + 18, ry + 4, s.color(if (selected) "title" else "text", 0xFF_F2EBF9.toInt()), false)
        }

        if (pages.size > VISIBLE_PAGE_ROWS) {
            val sbX = lx + listRect.w - 7
            val sbY = ly + 4
            val sbH = listRect.h - 8
            SkinRenderer.drawSprite(graphics, s, "scrollbar_track", sbX, sbY, 4, sbH)
            val thumbH = maxOf(10, sbH * VISIBLE_PAGE_ROWS / pages.size)
            val thumbY = sbY + ((sbH - thumbH) * pageScrollOffset / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, s, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }
    }

    private fun renderSnippetPaper(graphics: GuiGraphics, s: GuiSkin) {
        val paper = r("snippet_paper", 128, 38, 178, 150)
        val px = guiLeft + paper.x
        val py = guiTop + paper.y
        SkinRenderer.drawSprite(graphics, s, "book_page_right", px, py, paper.w, paper.h)

        val pages = entry.pages
        if (pages.isEmpty()) return
        selectedPageIdx = selectedPageIdx.coerceIn(0, pages.lastIndex)
        val p = pages[selectedPageIdx]
        val ink = s.color("paper_text", 0xFF_2B1F16.toInt())
        val dim = s.color("paper_text_dim", 0xFF_6B5844.toInt())

        SkinRenderer.drawSprite(graphics, s, "icon.page.${NoteVisualHelpers.pageIconSuffix(p.type)}", px + 10, py + 10, 12, 12)
        val headingText = p.title.ifBlank { I18n.get(p.type.langKey) }
        graphics.drawString(font, font.plainSubstrByWidth(headingText, paper.w - 36), px + 26, py + 12, ink, false)
        SkinRenderer.drawDashedHLine(graphics, px + 10, py + 26, paper.w - 20, dim and 0x55FFFFFF)

        var textTop = py + 32
        val visualOffset = renderPageThumbnail(graphics, s, p, px + 10, py + 30, paper.w - 20)
        textTop += visualOffset

        val availH = (py + paper.h - 10 - textTop).coerceAtLeast(0)
        val maxLines = availH / 9
        if (maxLines > 0) {
            val clean = NoteLibraryScreen.cleanSnippet(p.text)
            if (clean.isEmpty()) {
                if (visualOffset == 0) {
                    graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.library.no_snippet"), px + 10, textTop + 4, dim, false)
                }
            } else {
                val lines = font.split(Component.literal(clean), paper.w - 22)
                var ly = textTop
                for (line in lines.take(maxLines)) {
                    graphics.drawString(font, line, px + 10, ly, ink, false)
                    ly += 9
                }
            }
        }
    }

    private fun renderPageThumbnail(graphics: GuiGraphics, s: GuiSkin, p: NotePage, x: Int, y: Int, w: Int): Int {
        return when (p.type) {
            NotePageType.SPOTLIGHT -> {
                SkinRenderer.drawSprite(graphics, s, "paper_slot", x, y, 22, 22)
                val stack = NoteVisualHelpers.resolveItemStack(p.field("item", "minecraft:paper"))
                graphics.renderItem(stack, x + 3, y + 3)
                graphics.drawString(font, font.plainSubstrByWidth(stack.hoverName.string, w - 28), x + 26, y + 7, s.color("paper_text", 0xFF_2B1F16.toInt()), false)
                28
            }
            NotePageType.HEX_PATTERN -> {
                val rl = ResourceLocation.tryParse(p.field("op_id").trim())
                val act = rl?.let { HexActions.REGISTRY.get(it) }
                SkinRenderer.drawSprite(graphics, s, "sub_panel", x, y, 42, 42)
                if (act != null) {
                    NoteVisualHelpers.renderHexPattern(graphics, act.prototype(), x + 3, y + 3, 36, 36, stroke = 2.4, hexSize = 10.0, key = "abw_scrap_act")
                }
                46
            }
            NotePageType.HEX_MANUAL_PATTERN -> {
                SkinRenderer.drawSprite(graphics, s, "sub_panel", x, y, 42, 42)
                p.patterns.firstOrNull()?.let { def ->
                    val dir = runCatching { HexDir.fromString(def.startDir.uppercase(Locale.ROOT)) }.getOrNull() ?: HexDir.EAST
                    val pat = runCatching { cn.xm1221.abadoned_greatwork.api.patternFromAngles(def.signature, dir) }.getOrNull()
                    if (pat != null) {
                        NoteVisualHelpers.renderHexPattern(graphics, pat, x + 3, y + 3, 36, 36, stroke = 2.4, hexSize = 10.0, key = "abw_scrap_man")
                    }
                }
                46
            }
            NotePageType.IOTA_DISPLAY -> {
                SkinRenderer.drawSprite(graphics, s, "paper_sub_panel", x, y, w, 46)
                val registries = Minecraft.getInstance().level?.registryAccess()
                for (i in 0 until minOf(3, p.iotas.size)) {
                    val tag = p.iotas[i]
                    val col = (runCatching { cn.xm1221.abadoned_greatwork.api.iotaColor(tag, registries) }.getOrDefault(0x6B5844)) or 0xFF_000000.toInt()
                    val disp = runCatching { cn.xm1221.abadoned_greatwork.api.iotaDisplay(tag, registries) }.getOrElse { Component.literal("?") }
                    graphics.fill(x + 4, y + 5 + i * 13, x + 7, y + 14 + i * 13, col)
                    val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(disp, w - 16))
                    graphics.drawString(font, trimmed, x + 10, y + 6 + i * 13, s.color("paper_text", 0xFF_2B1F16.toInt()), false)
                }
                50
            }
            else -> 0
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        val listRect = r("page_list", 10, 38, 114, 150)
        val lx = guiLeft + listRect.x
        val ly = guiTop + listRect.y
        if (button == 0 && mouseX >= lx && mouseX < lx + listRect.w && mouseY >= ly && mouseY < ly + listRect.h) {
            val rel = ((mouseY - (ly + 4)) / PAGE_ROW_H).toInt()
            val idx = pageScrollOffset + rel
            if (rel in 0 until VISIBLE_PAGE_ROWS && idx in entry.pages.indices) {
                selectedPageIdx = idx
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val listRect = r("page_list", 10, 38, 114, 150)
        val lx = guiLeft + listRect.x
        val ly = guiTop + listRect.y
        if (mouseX >= lx && mouseX < lx + listRect.w && mouseY >= ly && mouseY < ly + listRect.h) {
            val maxScroll = (entry.pages.size - VISIBLE_PAGE_ROWS).coerceAtLeast(0)
            if (maxScroll > 0) {
                pageScrollOffset = (pageScrollOffset - scrollY.toInt().coerceIn(-1, 1)).coerceIn(0, maxScroll)
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    companion object {
        private const val VISIBLE_PAGE_ROWS = 8
        private const val PAGE_ROW_H = 17
    }
}
