package cn.xm1221.abadoned_greatwork.client.notes.screen

import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRect
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinSuggestBox
import cn.xm1221.abadoned_greatwork.client.gui.skin.SuggestOption
import cn.xm1221.abadoned_greatwork.client.notes.NoteFileIO
import cn.xm1221.abadoned_greatwork.client.notes.NotePatchouliCompat
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteDeleteC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteReorderC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteWriteScrapC2S
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * 贴图驱动的个人笔记库界面：浏览、排序、删除、写入笔记残页、JSON 导入导出以及进入编辑器。
 */
class NoteLibraryScreen : Screen(Component.translatable("abadoned_greatwork.notes.library_title")) {

    private val skin: GuiSkin get() = GuiSkinLoader.get("note_library")

    private var winX: Int = 0
    private var winY: Int = 0
    private var selectedIndex: Int = 0
    private var listScroll: Int = 0
    private var confirmDeleteId: String? = null
    private var lastClickIndex: Int = -1
    private var lastClickTime: Long = 0L

    private var localStatus: Component? = null
    private var localStatusOk: Boolean = true

    private lateinit var btnNew: SkinButton
    private lateinit var btnEdit: SkinButton
    private lateinit var btnOpenBook: SkinButton
    private lateinit var btnUp: SkinButton
    private lateinit var btnDown: SkinButton
    private lateinit var btnDelete: SkinButton
    private lateinit var btnWriteScrap: SkinButton
    private lateinit var btnExport: SkinButton
    private lateinit var btnImport: SkinButton

    private val importSuggest = SkinSuggestBox { skin }

    private val syncListener: () -> Unit = {
        Minecraft.getInstance().execute {
            val count = ClientNoteLibrary.entries.size
            if (selectedIndex >= count) selectedIndex = (count - 1).coerceAtLeast(0)
            confirmDeleteId = null
            updateButtonStates()
        }
    }

    override fun init() {
        ClientNoteLibrary.addListener(syncListener)
        val s = skin
        winX = ((width - s.layoutWidth) / 2).coerceAtLeast(0)
        winY = ((height - s.layoutHeight) / 2).coerceAtLeast(0)

        val detail = s.region("detail_panel", SkinRect(170, 26, 196, 164))
        val footL = s.region("footer_left", SkinRect(10, 196, 154, 20))
        val footR = s.region("footer_right", SkinRect(170, 196, 196, 20))

        val dx = winX + detail.x + 8
        val dy = winY + detail.y + detail.h - 78
        val colW = (detail.w - 20) / 2

        btnNew = addRenderableWidget(
            SkinButton({ skin }, dx, dy, colW, 16, Component.translatable("abadoned_greatwork.notes.btn.new"), "button_accent", "icon.add") {
                openEditorForNew()
            }
        )
        btnEdit = addRenderableWidget(
            SkinButton({ skin }, dx + colW + 4, dy, colW, 16, Component.translatable("abadoned_greatwork.notes.btn.edit"), "button_accent") {
                selectedEntry()?.let { openEditorFor(it) }
            }
        )
        btnOpenBook = addRenderableWidget(
            SkinButton({ skin }, dx, dy + 20, colW, 16, Component.translatable("abadoned_greatwork.notes.btn.open_book"), "button", "icon.book") {
                val entry = selectedEntry() ?: return@SkinButton
                if (NotePatchouliCompat.isAvailable()) {
                    NotePatchouliCompat.openEntryInBook(entry.id, 0)
                } else {
                    setStatus(false, Component.translatable("abadoned_greatwork.notes.error.no_patchouli"))
                }
            }
        )
        btnWriteScrap = addRenderableWidget(
            SkinButton({ skin }, dx + colW + 4, dy + 20, colW, 16, Component.translatable("abadoned_greatwork.notes.btn.write_scrap"), "button", "icon.scrap") {
                val entry = selectedEntry() ?: return@SkinButton
                MsgNoteWriteScrapC2S(entry.id).sendToServer()
            }
        )

        val smallW = 24
        btnUp = addRenderableWidget(
            SkinButton({ skin }, dx, dy + 40, smallW, 16, Component.empty(), "button", "icon.up", Component.translatable("abadoned_greatwork.notes.btn.move_up")) {
                selectedEntry()?.let { MsgNoteReorderC2S(it.id, -1).sendToServer() }
            }
        )
        btnDown = addRenderableWidget(
            SkinButton({ skin }, dx + smallW + 4, dy + 40, smallW, 16, Component.empty(), "button", "icon.down", Component.translatable("abadoned_greatwork.notes.btn.move_down")) {
                selectedEntry()?.let { MsgNoteReorderC2S(it.id, 1).sendToServer() }
            }
        )
        btnDelete = addRenderableWidget(
            SkinButton({ skin }, dx + smallW * 2 + 10, dy + 40, detail.w - 16 - (smallW * 2 + 10), 16, Component.translatable("abadoned_greatwork.notes.btn.delete"), "button_danger", "icon.delete") {
                handleDeleteClick()
            }
        )

        val flx = winX + footL.x
        val fly = winY + footL.y + 2
        val halfFoot = (footL.w - 4) / 2
        btnExport = addRenderableWidget(
            SkinButton({ skin }, flx, fly, halfFoot, 16, Component.translatable("abadoned_greatwork.notes.btn.export"), "button", "icon.export") {
                val entry = selectedEntry() ?: return@SkinButton
                NoteFileIO.exportEntry(entry).onSuccess { path ->
                    setStatus(true, Component.translatable("abadoned_greatwork.notes.exported", path.fileName.toString()))
                }.onFailure { err ->
                    setStatus(false, Component.translatable("abadoned_greatwork.notes.error.io", err.message ?: "error"))
                }
            }
        )
        btnImport = addRenderableWidget(
            SkinButton({ skin }, flx + halfFoot + 4, fly, halfFoot, 16, Component.translatable("abadoned_greatwork.notes.btn.import"), "button", "icon.import") {
                openImportPicker()
            }
        )

        val frx = winX + footR.x
        val fry = winY + footR.y + 2
        addRenderableWidget(
            SkinButton({ skin }, frx + footR.w - 56, fry, 56, 16, Component.translatable("abadoned_greatwork.notes.btn.close"), "button") {
                onClose()
            }
        )

        updateButtonStates()
    }

    override fun removed() {
        ClientNoteLibrary.removeListener(syncListener)
        super.removed()
    }

    private fun selectedEntry(): NoteEntry? =
        ClientNoteLibrary.entries.getOrNull(selectedIndex)

    private fun updateButtonStates() {
        val list = ClientNoteLibrary.entries
        val hasSel = selectedIndex in list.indices
        btnEdit.active = hasSel
        btnOpenBook.active = hasSel && NotePatchouliCompat.isAvailable()
        btnWriteScrap.active = hasSel
        btnDelete.active = hasSel
        btnExport.active = hasSel
        btnUp.active = hasSel && selectedIndex > 0
        btnDown.active = hasSel && selectedIndex < list.lastIndex

        val confirmArmed = hasSel && confirmDeleteId == list[selectedIndex].id
        btnDelete.message = Component.translatable(
            if (confirmArmed) "abadoned_greatwork.notes.btn.delete_confirm" else "abadoned_greatwork.notes.btn.delete"
        )
    }

    private fun handleDeleteClick() {
        val entry = selectedEntry() ?: return
        if (confirmDeleteId == entry.id) {
            confirmDeleteId = null
            MsgNoteDeleteC2S(entry.id).sendToServer()
        } else {
            confirmDeleteId = entry.id
            updateButtonStates()
        }
    }

    private fun openEditorForNew() {
        val playerName = minecraft?.player?.gameProfile?.name.orEmpty()
        val blank = NoteEntry(
            id = UUID.randomUUID().toString(),
            title = "",
            author = playerName,
            time = System.currentTimeMillis(),
        )
        minecraft?.setScreen(NoteEditorScreen(blank, this))
    }

    private fun openEditorFor(entry: NoteEntry) {
        minecraft?.setScreen(NoteEditorScreen(entry, this))
    }

    private fun openImportPicker() {
        val files = NoteFileIO.listJsonFiles()
        if (files.isEmpty()) {
            setStatus(false, Component.translatable("abadoned_greatwork.notes.error.no_export_files"))
            return
        }
        val footL = skin.region("footer_left", SkinRect(10, 196, 154, 20))
        val options = files.map { SuggestOption(it, it) }
        val px = winX + footL.x
        val py = (winY + footL.y - 108).coerceAtLeast(8)
        importSuggest.show(px, py, 200, 104, options, "") { chosen ->
            val res = NoteFileIO.importAndSendToServer(chosen.value)
            res.onSuccess { entry ->
                val title = entry.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
                setStatus(true, Component.translatable("abadoned_greatwork.notes.imported", title))
            }.onFailure { err ->
                val k = err.message ?: "abadoned_greatwork.notes.error.io"
                val comp = if (k.startsWith("abadoned_greatwork.")) Component.translatable(k)
                else Component.translatable("abadoned_greatwork.notes.error.io", k)
                setStatus(false, comp)
            }
        }
    }

    private fun setStatus(ok: Boolean, msg: Component) {
        localStatusOk = ok
        localStatus = msg
        ClientNoteLibrary.clearResult()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(graphics)
        val s = skin
        SkinRenderer.drawSprite(graphics, s, "panel", winX, winY, s.layoutWidth, s.layoutHeight)

        val titleBar = s.region("title_bar", SkinRect(10, 8, 356, 16))
        val listRect = s.region("entry_list", SkinRect(10, 26, 154, 164))
        val detailRect = s.region("detail_panel", SkinRect(170, 26, 196, 164))

        val maxEntries = Abadoned_greatworkServerConfig.config.maxEntriesPerPlayer
        val entries = ClientNoteLibrary.entries

        // Header
        graphics.drawString(
            font,
            title,
            winX + titleBar.x + 2,
            winY + titleBar.y + 4,
            s.color("title", 0xFF_F2E6FF.toInt()),
            false,
        )
        val countText = "${entries.size} / $maxEntries"
        graphics.drawString(
            font,
            countText,
            winX + titleBar.x + titleBar.w - font.width(countText) - 2,
            winY + titleBar.y + 4,
            s.color("text_dim", 0xFF_9E8EB8.toInt()),
            false,
        )

        // Entry List Panel
        val lx = winX + listRect.x
        val ly = winY + listRect.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", lx, ly, listRect.w, listRect.h)

        val rowH = 22
        val visibleRows = ((listRect.h - 8) / rowH).coerceAtLeast(1)
        val maxScroll = (entries.size - visibleRows).coerceAtLeast(0)
        listScroll = listScroll.coerceIn(0, maxScroll)

        if (entries.isEmpty()) {
            graphics.drawWordWrap(
                font,
                Component.translatable("abadoned_greatwork.notes.empty_library"),
                lx + 8,
                ly + 10,
                listRect.w - 16,
                s.color("text_dim", 0xFF_9E8EB8.toInt()),
            )
        } else {
            val end = minOf(entries.size, listScroll + visibleRows)
            for (i in listScroll until end) {
                val ry = ly + 4 + (i - listScroll) * rowH
                val itemW = listRect.w - (if (entries.size > visibleRows) 14 else 8)
                val isSelected = i == selectedIndex
                val rowSprite = if (isSelected) "list_item_selected" else "list_item_normal"
                SkinRenderer.drawSprite(graphics, s, rowSprite, lx + 4, ry, itemW, rowH - 2)

                val entry = entries[i]
                graphics.renderItem(resolveIconStack(entry.icon), lx + 7, ry + 2)
                val titleStr = entry.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
                val trimmed = font.plainSubstrByWidth(titleStr, itemW - 50)
                graphics.drawString(font, trimmed, lx + 26, ry + 3, s.color("text", 0xFF_EDE4FA.toInt()), false)
                val sub = Component.translatable("abadoned_greatwork.notes.page_count", entry.pages.size).string
                graphics.drawString(font, sub, lx + 26, ry + 11, s.color("text_dim", 0xFF_9E8EB8.toInt()), false)
            }
            if (entries.size > visibleRows) {
                val sbX = lx + listRect.w - 8
                val sbY = ly + 4
                val sbH = listRect.h - 8
                SkinRenderer.drawSprite(graphics, s, "scrollbar_track", sbX, sbY, 4, sbH)
                val thumbH = maxOf(10, sbH * visibleRows / entries.size)
                val thumbY = sbY + ((sbH - thumbH) * listScroll / maxScroll.coerceAtLeast(1))
                SkinRenderer.drawSprite(graphics, s, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
            }
        }

        // Detail Panel
        val dx = winX + detailRect.x
        val dy = winY + detailRect.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", dx, dy, detailRect.w, detailRect.h)

        val sel = selectedEntry()
        if (sel != null) {
            SkinRenderer.drawSprite(graphics, s, "slot_frame", dx + 8, dy + 8, 22, 22)
            graphics.renderItem(resolveIconStack(sel.icon), dx + 11, dy + 11)

            val titleStr = sel.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
            graphics.drawString(
                font,
                font.plainSubstrByWidth(titleStr, detailRect.w - 42),
                dx + 34,
                dy + 10,
                s.color("title", 0xFF_F2E6FF.toInt()),
                false,
            )
            val dateStr = if (sel.time > 0L) DATE_FMT.format(Date(sel.time)) else ""
            val meta = if (sel.author.isNotBlank()) "${sel.author} · $dateStr" else dateStr
            graphics.drawString(
                font,
                font.plainSubstrByWidth(meta, detailRect.w - 42),
                dx + 34,
                dy + 20,
                s.color("text_dim", 0xFF_9E8EB8.toInt()),
                false,
            )

            // Page type breakdown icons
            val paperCost = sel.pages.size.coerceAtLeast(1) * Abadoned_greatworkServerConfig.config.paperPerPage.coerceIn(0, 16)
            graphics.drawString(
                font,
                Component.translatable("abadoned_greatwork.notes.paper_cost", paperCost),
                dx + 8,
                dy + 36,
                s.color("text_dim", 0xFF_9E8EB8.toInt()),
                false,
            )

            val maxIcons = minOf(sel.pages.size, 11)
            for (pi in 0 until maxIcons) {
                val ix = dx + 8 + (pi % 11) * 16
                val iy = dy + 48
                SkinRenderer.drawSprite(graphics, s, "icon.page.${pageIconSuffix(sel.pages[pi].type)}", ix, iy, 12, 12)
            }
            if (sel.pages.size > maxIcons) {
                graphics.drawString(
                    font,
                    "+${sel.pages.size - maxIcons}",
                    dx + 8,
                    dy + 64,
                    s.color("text_dim", 0xFF_9E8EB8.toInt()),
                    false,
                )
            }
        } else {
            graphics.drawWordWrap(
                font,
                Component.translatable("abadoned_greatwork.notes.select_hint"),
                dx + 10,
                dy + 14,
                detailRect.w - 20,
                s.color("text_dim", 0xFF_9E8EB8.toInt()),
            )
        }

        // Status message in footer_right (left of Close button)
        val statusMsg = ClientNoteLibrary.lastResultMessage ?: localStatus
        val statusOk = if (ClientNoteLibrary.lastResultMessage != null) ClientNoteLibrary.lastResultSuccess else localStatusOk
        if (statusMsg != null) {
            val footR = s.region("footer_right", SkinRect(170, 196, 196, 20))
            val col = if (statusOk) s.color("ok", 0xFF_78E092.toInt()) else s.color("error", 0xFF_EC6270.toInt())
            val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(statusMsg, (footR.w - 64).coerceAtLeast(40)))
            graphics.drawString(font, trimmed, winX + footR.x, winY + footR.y + 6, col, false)
        }

        super.render(graphics, mouseX, mouseY, partialTicks)
        importSuggest.render(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (importSuggest.open && importSuggest.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        val listRect = skin.region("entry_list", SkinRect(10, 26, 154, 164))
        val lx = winX + listRect.x
        val ly = winY + listRect.y
        if (button == 0 && mouseX >= lx + 4 && mouseX < lx + listRect.w - 8 && mouseY >= ly + 4 && mouseY < ly + listRect.h - 4) {
            val rowH = 22
            val row = ((mouseY - (ly + 4)) / rowH).toInt()
            val idx = listScroll + row
            val entries = ClientNoteLibrary.entries
            if (row >= 0 && idx in entries.indices) {
                selectedIndex = idx
                confirmDeleteId = null
                updateButtonStates()
                val now = System.currentTimeMillis()
                if (lastClickIndex == idx && now - lastClickTime < 350L) {
                    openEditorFor(entries[idx])
                }
                lastClickIndex = idx
                lastClickTime = now
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        if (importSuggest.open && importSuggest.mouseScrolled(mouseX, mouseY, delta)) {
            return true
        }
        val listRect = skin.region("entry_list", SkinRect(10, 26, 154, 164))
        val lx = winX + listRect.x
        val ly = winY + listRect.y
        if (mouseX >= lx && mouseX < lx + listRect.w && mouseY >= ly && mouseY < ly + listRect.h) {
            val rowH = 22
            val visibleRows = ((listRect.h - 8) / rowH).coerceAtLeast(1)
            val maxScroll = (ClientNoteLibrary.entries.size - visibleRows).coerceAtLeast(0)
            listScroll = (listScroll - delta.toInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, delta)
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm")

        fun resolveIconStack(itemStr: String): ItemStack {
            val rl = ResourceLocation.tryParse(itemStr)
            val item = if (rl != null) BuiltInRegistries.ITEM.get(rl) else Items.BOOK
            return ItemStack(if (item == Items.AIR) Items.BOOK else item)
        }

        fun pageIconSuffix(type: cn.xm1221.abadoned_greatwork.notes.NotePageType): String = when (type) {
            cn.xm1221.abadoned_greatwork.notes.NotePageType.TEXT -> "text"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.SPOTLIGHT -> "spotlight"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.CRAFTING -> "crafting"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.SMELTING -> "smelting"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.HEX_PATTERN -> "pattern"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.HEX_MANUAL_PATTERN -> "manual_pattern"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.IMAGE -> "image"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.ENTITY -> "entity"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.LINK -> "link"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.EMPTY -> "empty"
            cn.xm1221.abadoned_greatwork.notes.NotePageType.IOTA_DISPLAY -> "iota_display"
        }
    }
}
