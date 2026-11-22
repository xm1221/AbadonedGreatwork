package cn.xm1221.abadoned_greatwork.client.notes.screen

import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRect
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.notes.NoteFileIO
import cn.xm1221.abadoned_greatwork.client.notes.NotePatchouliCompat
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.DeleteConfirmModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.JsonImportModal
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteDeleteC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteReorderC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteWriteScrapC2S
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 玩家笔记库主界面：左侧可搜索与滚轮导航条目目录 + 本地导入导出；
 * 右侧羊皮纸详情卡展示摘要、页型图标序列、分组操作及“抄录笔记碎片（需 X / 现有 Y 张纸）”状态。
 */
class NoteLibraryScreen : Screen(Component.translatable("abadoned_greatwork.notes.library.title")) {

    private var guiLeft: Int = 0
    private var guiTop: Int = 0
    private var guiW: Int = 376
    private var guiH: Int = 224

    private lateinit var searchField: SkinTextField
    private lateinit var newBtn: SkinButton
    private lateinit var closeXBtn: SkinButton
    private lateinit var importBtn: SkinButton
    private lateinit var exportBtn: SkinButton

    private lateinit var openBookBtn: SkinButton
    private lateinit var editBtn: SkinButton
    private lateinit var moveUpBtn: SkinButton
    private lateinit var moveDownBtn: SkinButton
    private lateinit var deleteBtn: SkinButton
    private lateinit var writeScrapBtn: SkinButton

    private var searchQuery: String = ""
    private var filteredEntries: List<NoteEntry> = emptyList()
    private var selectedEntryId: String? = null
    private var scrollOffset: Int = 0
    private var draggingScrollbar: Boolean = false
    private var dragOffsetY: Int = 0

    private var lastClickEntryId: String? = null
    private var lastClickTimeMs: Long = 0L

    private var activeModal: SkinModal? = null
    private var localNotice: Pair<Component, Boolean>? = null
    private var localNoticeUntilMs: Long = 0L

    private val pageIconTooltips = mutableListOf<Pair<SkinRect, Component>>()

    private val skin: GuiSkin get() = GuiSkinLoader.get("note_library")

    private val libListener: () -> Unit = {
        Minecraft.getInstance().execute {
            refreshFilteredList()
            updateActionButtons()
        }
    }

    private val resultListener: (Boolean, String, List<String>) -> Unit = { success, messageKey, args ->
        Minecraft.getInstance().execute {
            if (messageKey.isNotBlank()) {
                showNotice(Component.translatable(messageKey, *args.toTypedArray()), isError = !success)
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
        ClientNoteLibrary.removeListener(libListener)
        ClientNoteLibrary.addListener(libListener)
        ClientNoteLibrary.removeResultListener(resultListener)
        ClientNoteLibrary.addResultListener(resultListener)

        val titleRect = r("title_bar", 10, 8, 356, 16)
        closeXBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + titleRect.x + titleRect.w - 15, guiTop + titleRect.y, 15, 15, Component.empty(), "button_danger", "icon.delete") {
                onClose()
            }
        )

        val searchRect = r("search_bar", 10, 26, 108, 16)
        searchField = addRenderableWidget(
            SkinTextField({ skin }, font, guiLeft + searchRect.x, guiTop + searchRect.y, searchRect.w, searchRect.h, Component.translatable("abadoned_greatwork.notes.search_hint"), "dark_field")
        )
        searchField.setMaxLength(64)
        searchField.setHint(Component.translatable("abadoned_greatwork.notes.search_hint"))
        searchField.value = searchQuery
        searchField.setResponder { q ->
            searchQuery = q
            refreshFilteredList()
            updateActionButtons()
        }

        val newRect = r("btn_new", 120, 26, 46, 16)
        newBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + newRect.x, guiTop + newRect.y, newRect.w, newRect.h, Component.translatable("abadoned_greatwork.notes.btn.new"), "button_accent", "icon.add") {
                val playerName = minecraft?.player?.gameProfile?.name.orEmpty()
                val blank = NoteEntry(author = playerName)
                minecraft?.setScreen(NoteEditorScreen(blank, this))
            }
        )

        val fl = r("footer_left", 10, 196, 156, 18)
        val fw = (fl.w - 4) / 2
        importBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + fl.x, guiTop + fl.y + 1, fw, 16, Component.translatable("abadoned_greatwork.notes.btn.import_json"), "button", "icon.import") {
                openModal(
                    JsonImportModal(onDismiss = {}) { fileName ->
                        NoteFileIO.importAndSendToServer(fileName)
                            .onSuccess { imported ->
                                selectedEntryId = imported.id
                                showNotice(Component.translatable("abadoned_greatwork.notes.notice.imported", fileName), isError = false)
                            }
                            .onFailure { err ->
                                showNotice(Component.literal(err.message ?: "Import failed"), isError = true)
                            }
                    }
                )
            }
        )
        exportBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + fl.x + fw + 4, guiTop + fl.y + 1, fw, 16, Component.translatable("abadoned_greatwork.notes.btn.export_json"), "button", "icon.export") {
                val sel = selectedEntry() ?: return@SkinButton
                NoteFileIO.exportEntry(sel)
                    .onSuccess { path ->
                        showNotice(Component.translatable("abadoned_greatwork.notes.notice.exported", path.fileName.toString()), isError = false)
                    }
                    .onFailure { err ->
                        showNotice(Component.literal(err.message ?: "Export failed"), isError = true)
                    }
            }
        )

        val detail = r("detail_paper", 172, 26, 194, 165)
        val dx = guiLeft + detail.x + 10
        val dy = guiTop + detail.y
        val dw = detail.w - 20
        val halfW = (dw - 4) / 2

        openBookBtn = addRenderableWidget(
            SkinButton({ skin }, dx, dy + 118, halfW, 16, Component.translatable("abadoned_greatwork.notes.btn.open_book"), "paper_button", "icon.book") {
                selectedEntry()?.let { sel -> NotePatchouliCompat.openEntryInBook(sel.id, 0) }
            }
        )
        editBtn = addRenderableWidget(
            SkinButton({ skin }, dx + halfW + 4, dy + 118, halfW, 16, Component.translatable("abadoned_greatwork.notes.btn.edit"), "button_accent", "icon.draw") {
                selectedEntry()?.let { sel -> minecraft?.setScreen(NoteEditorScreen(sel, this)) }
            }
        )

        moveUpBtn = addRenderableWidget(
            SkinButton({ skin }, dx, dy + 138, 26, 16, Component.empty(), "paper_button", "icon.up") {
                selectedEntry()?.let { sel -> MsgNoteReorderC2S(sel.id, -1).sendToServer() }
            }
        )
        moveDownBtn = addRenderableWidget(
            SkinButton({ skin }, dx + 29, dy + 138, 26, 16, Component.empty(), "paper_button", "icon.down") {
                selectedEntry()?.let { sel -> MsgNoteReorderC2S(sel.id, 1).sendToServer() }
            }
        )
        deleteBtn = addRenderableWidget(
            SkinButton({ skin }, dx + 59, dy + 138, dw - 59, 16, Component.translatable("abadoned_greatwork.notes.btn.delete"), "button_danger", "icon.delete") {
                val sel = selectedEntry() ?: return@SkinButton
                openModal(
                    DeleteConfirmModal(sel.title, onDismiss = {}) {
                        MsgNoteDeleteC2S(sel.id).sendToServer()
                    }
                )
            }
        )

        val fr = r("footer_right", 172, 196, 194, 18)
        writeScrapBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + fr.x, guiTop + fr.y + 1, fr.w, 16, Component.empty(), "button_accent", "icon.scrap") {
                selectedEntry()?.let { sel -> MsgNoteWriteScrapC2S(sel.id).sendToServer() }
            }
        )

        refreshFilteredList()
        updateActionButtons()
        activeModal?.init(width, height)
    }

    override fun removed() {
        ClientNoteLibrary.removeListener(libListener)
        ClientNoteLibrary.removeResultListener(resultListener)
        activeModal?.onClose()
        activeModal = null
        super.removed()
    }

    override fun tick() {
        activeModal?.tick()
    }

    private fun r(key: String, dx: Int, dy: Int, dw: Int, dh: Int): SkinRect =
        skin.region(key, SkinRect(dx, dy, dw, dh))

    private fun refreshFilteredList() {
        val all = ClientNoteLibrary.entries
        val q = searchQuery.trim().lowercase()
        filteredEntries = if (q.isEmpty()) {
            all
        } else {
            all.filter { it.title.lowercase().contains(q) || it.author.lowercase().contains(q) }
        }
        if (filteredEntries.none { it.id == selectedEntryId }) {
            selectedEntryId = filteredEntries.firstOrNull()?.id
        }
        val maxScroll = (filteredEntries.size - LIST_VISIBLE_ROWS).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)
    }

    private fun selectedEntry(): NoteEntry? =
        filteredEntries.firstOrNull { it.id == selectedEntryId }

    private fun updateActionButtons() {
        val limits = NoteValidator.currentLimits()
        newBtn.active = ClientNoteLibrary.entries.size < limits.maxEntriesPerPlayer

        val sel = selectedEntry()
        val hasSel = sel != null
        exportBtn.active = hasSel
        openBookBtn.active = hasSel && NotePatchouliCompat.isAvailable()
        editBtn.active = hasSel
        deleteBtn.active = hasSel

        val allIdx = if (sel == null) -1 else ClientNoteLibrary.entries.indexOfFirst { it.id == sel.id }
        moveUpBtn.active = hasSel && allIdx > 0
        moveDownBtn.active = hasSel && allIdx in 0 until ClientNoteLibrary.entries.lastIndex

        if (sel == null) {
            writeScrapBtn.active = false
            writeScrapBtn.message = Component.translatable("abadoned_greatwork.notes.btn.write_scrap_empty")
        } else {
            val need = sel.pages.size
            val creative = NoteVisualHelpers.isClientCreative()
            val have = NoteVisualHelpers.countClientPaper()
            writeScrapBtn.active = true
            writeScrapBtn.message = if (creative) {
                Component.translatable("abadoned_greatwork.notes.btn.write_scrap_empty")
            } else {
                Component.translatable("abadoned_greatwork.notes.btn.write_scrap_cost", need, have)
            }
        }
    }

    private fun showNotice(comp: Component, isError: Boolean) {
        localNotice = comp to isError
        localNoticeUntilMs = System.currentTimeMillis() + 3500L
    }

    private fun openModal(modal: SkinModal) {
        activeModal?.onClose()
        searchField.isFocused = false
        modal.setDismissHook {
            if (activeModal === modal) {
                activeModal = null
            }
        }
        modal.init(width, height)
        activeModal = modal
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(graphics)
        val s = skin
        updateActionButtons()

        SkinRenderer.drawSprite(graphics, s, "panel", guiLeft, guiTop, guiW, guiH)

        val titleBar = r("title_bar", 10, 8, 356, 16)
        graphics.drawString(font, title, guiLeft + titleBar.x + 2, guiTop + titleBar.y + 3, s.color("title", 0xFF_F2EBF9.toInt()), false)

        val countLabel = "${ClientNoteLibrary.entries.size}/${NoteValidator.currentLimits().maxEntriesPerPlayer}"
        graphics.drawString(font, countLabel, guiLeft + titleBar.x + titleBar.w - 22 - font.width(countLabel), guiTop + titleBar.y + 3, s.color("text_dim", 0xFF_A293B8.toInt()), false)

        val bgMouseX = if (activeModal == null) mouseX else -1
        val bgMouseY = if (activeModal == null) mouseY else -1

        renderEntryList(graphics, s, bgMouseX, bgMouseY)
        renderDetailCard(graphics, s)

        super.render(graphics, bgMouseX, bgMouseY, partialTicks)

        renderFeedbackBanner(graphics, s)

        if (activeModal == null) {
            for ((box, tip) in pageIconTooltips) {
                if (mouseX >= box.x && mouseX < box.x + box.w && mouseY >= box.y && mouseY < box.y + box.h) {
                    graphics.renderComponentTooltip(font, listOf(tip), mouseX, mouseY)
                    break
                }
            }
        } else {
            activeModal!!.render(graphics, mouseX, mouseY, partialTicks)
        }
    }

    private fun renderEntryList(graphics: GuiGraphics, s: GuiSkin, mouseX: Int, mouseY: Int) {
        val listRect = r("entry_list", 10, 45, 156, 146)
        val lx = guiLeft + listRect.x
        val ly = guiTop + listRect.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", lx, ly, listRect.w, listRect.h)

        if (filteredEntries.isEmpty()) {
            val emptyMsg = Component.translatable(
                if (ClientNoteLibrary.entries.isEmpty()) "abadoned_greatwork.notes.library.empty"
                else "abadoned_greatwork.notes.no_matches"
            )
            val lines = font.split(emptyMsg, listRect.w - 16)
            var ey = ly + 12
            for (line in lines.take(4)) {
                graphics.drawString(font, line, lx + 8, ey, s.color("text_dim", 0xFF_A293B8.toInt()), false)
                ey += 10
            }
            return
        }

        val maxScroll = (filteredEntries.size - LIST_VISIBLE_ROWS).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)
        val hasSb = filteredEntries.size > LIST_VISIBLE_ROWS
        val rowW = if (hasSb) listRect.w - 15 else listRect.w - 8

        val end = minOf(filteredEntries.size, scrollOffset + LIST_VISIBLE_ROWS)
        for (idx in scrollOffset until end) {
            val rel = idx - scrollOffset
            val rx = lx + 4
            val ry = ly + 5 + rel * LIST_ROW_H
            val rowH = LIST_ROW_H - 2
            val entry = filteredEntries[idx]
            val selected = entry.id == selectedEntryId
            val hovered = mouseX >= rx && mouseX < rx + rowW && mouseY >= ry && mouseY < ry + rowH
            val sprite = if (selected || hovered) "list_item_selected" else "list_item_normal"
            SkinRenderer.drawSprite(graphics, s, sprite, rx, ry, rowW, rowH)

            val accent = GuiSkin.parseHexColor("FF${entry.color}") ?: 0xFF_9B59B6.toInt()
            graphics.fill(rx + 2, ry + 3, rx + 4, ry + rowH - 3, accent)

            graphics.renderItem(NoteVisualHelpers.resolveItemStack(entry.icon), rx + 6, ry + 3)

            val titleStr = entry.title.ifBlank { I18n.get("abadoned_greatwork.notes.untitled") }
            val trimmedTitle = font.plainSubstrByWidth(titleStr, rowW - 28)
            graphics.drawString(font, trimmedTitle, rx + 25, ry + 3, s.color(if (selected) "title" else "text", 0xFF_F2EBF9.toInt()), false)

            val sub = Component.translatable("abadoned_greatwork.notes.library.row_pages", entry.pages.size)
            graphics.drawString(font, sub, rx + 25, ry + 12, s.color("text_dim", 0xFF_A293B8.toInt()), false)
        }

        if (hasSb) {
            val sbX = lx + listRect.w - 8
            val sbY = ly + 5
            val sbH = (listRect.h - 10).coerceAtLeast(16)
            SkinRenderer.drawSprite(graphics, s, "scrollbar_track", sbX, sbY, 4, sbH)
            val thumbH = maxOf(10, sbH * LIST_VISIBLE_ROWS / filteredEntries.size)
            val thumbY = sbY + ((sbH - thumbH) * scrollOffset / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, s, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }
    }

    private fun renderDetailCard(graphics: GuiGraphics, s: GuiSkin) {
        val detail = r("detail_paper", 172, 26, 194, 165)
        val dx = guiLeft + detail.x
        val dy = guiTop + detail.y
        SkinRenderer.drawSprite(graphics, s, "book_page_right", dx, dy, detail.w, detail.h)
        pageIconTooltips.clear()

        val ink = s.color("paper_text", 0xFF_2B1F16.toInt())
        val dim = s.color("paper_text_dim", 0xFF_6B5844.toInt())

        val sel = selectedEntry()
        if (sel == null) {
            val hint = font.split(Component.translatable("abadoned_greatwork.notes.library.select_hint"), detail.w - 24)
            var hy = dy + 56
            for (line in hint.take(3)) {
                graphics.drawString(font, line, dx + 12, hy, dim, false)
                hy += 10
            }
            return
        }

        SkinRenderer.drawSprite(graphics, s, "paper_slot", dx + 10, dy + 9, 22, 22)
        graphics.renderItem(NoteVisualHelpers.resolveItemStack(sel.icon), dx + 13, dy + 12)

        val accent = GuiSkin.parseHexColor("FF${sel.color}") ?: 0xFF_9B59B6.toInt()
        graphics.fill(dx + 36, dy + 10, dx + 39, dy + 22, accent)

        val titleStr = sel.title.ifBlank { I18n.get("abadoned_greatwork.notes.untitled") }
        graphics.drawString(font, font.plainSubstrByWidth(titleStr, detail.w - 52), dx + 42, dy + 11, ink, false)

        val meta = if (sel.author.isNotBlank()) {
            Component.translatable("abadoned_greatwork.notes.library.meta_author", sel.author, sel.pages.size)
        } else {
            Component.translatable("abadoned_greatwork.notes.library.meta_pages", sel.pages.size)
        }
        val metaTrimmed = Language.getInstance().getVisualOrder(font.substrByWidth(meta, detail.w - 48))
        graphics.drawString(font, metaTrimmed, dx + 36, dy + 23, dim, false)

        val maxIcons = 10
        var ix = dx + 10
        val iy = dy + 38
        for (i in 0 until minOf(sel.pages.size, maxIcons)) {
            val p = sel.pages[i]
            val suffix = NoteVisualHelpers.pageIconSuffix(p.type)
            SkinRenderer.drawSprite(graphics, s, "icon.page.$suffix", ix, iy, 12, 12)
            val pName = I18n.get(p.type.langKey)
            val tipText = if (p.title.isNotBlank()) "${i + 1}. $pName - ${p.title}" else "${i + 1}. $pName"
            pageIconTooltips += SkinRect(ix, iy, 12, 12) to Component.literal(tipText)
            ix += 15
        }
        if (sel.pages.size > maxIcons) {
            graphics.drawString(font, "+${sel.pages.size - maxIcons}", ix + 2, iy + 2, dim, false)
        }

        val snipY = dy + 54
        val snipH = 56
        SkinRenderer.drawSprite(graphics, s, "paper_sub_panel", dx + 10, snipY, detail.w - 20, snipH)
        val rawFirst = sel.pages.firstOrNull()?.text.orEmpty()
        val cleaned = cleanSnippet(rawFirst)
        if (cleaned.isEmpty()) {
            graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.library.no_snippet"), dx + 16, snipY + 8, dim, false)
        } else {
            val wrapped = font.split(Component.literal(cleaned), detail.w - 30)
            var ty = snipY + 6
            for (line in wrapped.take(5)) {
                graphics.drawString(font, line, dx + 15, ty, ink, false)
                ty += 9
            }
        }
    }

    private fun renderFeedbackBanner(graphics: GuiGraphics, s: GuiSkin) {
        val now = System.currentTimeMillis()
        val msgPair: Pair<Component, Boolean>? = if (localNotice != null && now < localNoticeUntilMs) localNotice else null
        val (comp, isErr) = msgPair ?: return
        val bw = (font.width(comp) + 16).coerceIn(100, guiW - 20)
        val bx = guiLeft + (guiW - bw) / 2
        val by = guiTop + guiH + 4
        SkinRenderer.drawSprite(graphics, s, "sub_panel", bx, by, bw, 16)
        val col = s.color(if (isErr) "error" else "ok", 0xFF_F2EBF9.toInt())
        SkinRenderer.drawCenteredNoShadow(graphics, font, Language.getInstance().getVisualOrder(font.substrByWidth(comp, bw - 10)), bx + bw / 2, by + 4, col)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        activeModal?.let { return it.mouseClicked(mouseX, mouseY, button) }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            if (!searchField.isFocused) searchField.isFocused = false
            return true
        }

        val listRect = r("entry_list", 10, 45, 156, 146)
        val lx = guiLeft + listRect.x
        val ly = guiTop + listRect.y
        if (button == 0 && mouseX >= lx && mouseX < lx + listRect.w && mouseY >= ly && mouseY < ly + listRect.h) {
            searchField.isFocused = false
            val maxScroll = (filteredEntries.size - LIST_VISIBLE_ROWS).coerceAtLeast(0)
            val sbX = lx + listRect.w - 9
            if (maxScroll > 0 && mouseX >= sbX) {
                val sbY = ly + 5
                val sbH = (listRect.h - 10).coerceAtLeast(16)
                val thumbH = maxOf(10, sbH * LIST_VISIBLE_ROWS / filteredEntries.size)
                val thumbY = sbY + ((sbH - thumbH) * scrollOffset / maxScroll)
                if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                    draggingScrollbar = true
                    dragOffsetY = (mouseY - thumbY).toInt()
                } else {
                    val frac = ((mouseY - sbY - thumbH / 2.0) / (sbH - thumbH).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    scrollOffset = (frac * maxScroll).toInt().coerceIn(0, maxScroll)
                    draggingScrollbar = true
                    dragOffsetY = thumbH / 2
                }
                return true
            }

            val relRow = ((mouseY - (ly + 5)) / LIST_ROW_H).toInt()
            val idx = scrollOffset + relRow
            if (relRow in 0 until LIST_VISIBLE_ROWS && idx in filteredEntries.indices) {
                val clicked = filteredEntries[idx]
                val now = System.currentTimeMillis()
                if (clicked.id == lastClickEntryId && now - lastClickTimeMs < 350L) {
                    minecraft?.setScreen(NoteEditorScreen(clicked, this))
                    return true
                }
                selectedEntryId = clicked.id
                lastClickEntryId = clicked.id
                lastClickTimeMs = now
                updateActionButtons()
                return true
            }
        }

        searchField.isFocused = false
        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        activeModal?.let { return it.mouseDragged(mouseX, mouseY, button, dragX, dragY) }
        if (draggingScrollbar && button == 0) {
            val listRect = r("entry_list", 10, 45, 156, 146)
            val ly = guiTop + listRect.y
            val maxScroll = (filteredEntries.size - LIST_VISIBLE_ROWS).coerceAtLeast(0)
            if (maxScroll > 0) {
                val sbY = ly + 5
                val sbH = (listRect.h - 10).coerceAtLeast(16)
                val thumbH = maxOf(10, sbH * LIST_VISIBLE_ROWS / filteredEntries.size)
                val track = (sbH - thumbH).coerceAtLeast(1)
                val frac = ((mouseY - dragOffsetY - sbY) / track).coerceIn(0.0, 1.0)
                scrollOffset = (frac * maxScroll).toInt().coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        activeModal?.let { return it.mouseReleased(mouseX, mouseY, button) }
        draggingScrollbar = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        activeModal?.let { return it.mouseScrolled(mouseX, mouseY, delta) }
        val listRect = r("entry_list", 10, 45, 156, 146)
        val lx = guiLeft + listRect.x
        val ly = guiTop + listRect.y
        if (mouseX >= lx && mouseX < lx + listRect.w && mouseY >= ly && mouseY < ly + listRect.h) {
            val maxScroll = (filteredEntries.size - LIST_VISIBLE_ROWS).coerceAtLeast(0)
            if (maxScroll > 0) {
                scrollOffset = (scrollOffset - delta.toInt().coerceIn(-1, 1)).coerceIn(0, maxScroll)
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        activeModal?.let { return it.keyPressed(keyCode, scanCode, modifiers) }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        activeModal?.let { return it.charTyped(codePoint, modifiers) }
        return super.charTyped(codePoint, modifiers)
    }

    companion object {
        private const val LIST_VISIBLE_ROWS = 6
        private const val LIST_ROW_H = 22
        private val MACRO_REGEX = Regex("""\$\([^)]*\)""")

        fun cleanSnippet(raw: String): String =
            raw.replace("$(br)", " ")
                .replace("$(br2)", " ")
                .replace("$(p)", " ")
                .replace(MACRO_REGEX, "")
                .replace('\n', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
    }
}
