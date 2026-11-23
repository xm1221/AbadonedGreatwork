package cn.xm1221.abadoned_greatwork.client.notes.screen

import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRect
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextArea
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.notes.NotePatchouliCompat
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.DiscardConfirmModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.EntryMetaModal
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteUpsertC2S
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NotePage
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 玩家视角“摊开书本工作台”笔记编辑器：
 * 左侧羊皮纸画布所见即所得拼装页面内容，右侧羊皮纸撰写与排版说明正文，
 * 顶部仅保留外观/标题/预览/保存/关闭，底部提供页码书签条与点击直达的校验栏。
 */
class NoteEditorScreen(
    initialEntry: NoteEntry,
    private val parentScreen: Screen? = null,
) : Screen(Component.translatable("abadoned_greatwork.notes.editor.title")) {

    private var entry: NoteEntry = initialEntry.copy(
        pages = if (initialEntry.pages.isEmpty()) listOf(NotePage()) else initialEntry.pages,
    )
    private var lastSavedSnapshot: NoteEntry = entry
    private var pendingSavedSnapshot: NoteEntry? = null
    private var pendingRawEntry: NoteEntry? = null
    private var pageIndex: Int = 0
    private var tabScrollOffset: Int = 0

    private var guiLeft: Int = 0
    private var guiTop: Int = 0
    private var guiW: Int = 396
    private var guiH: Int = 230

    private lateinit var titleField: SkinTextField
    private lateinit var metaBtn: SkinButton
    private lateinit var previewBtn: SkinButton
    private lateinit var saveBtn: SkinButton
    private lateinit var closeBtn: SkinButton

    private lateinit var addPageBtn: SkinButton
    private lateinit var moveLeftBtn: SkinButton
    private lateinit var moveRightBtn: SkinButton
    private lateinit var delPageBtn: SkinButton

    private lateinit var pageCanvas: NotePageCanvas
    private lateinit var textArea: SkinTextArea
    private lateinit var previewTextBtn: SkinButton
    private var previewTextMode: Boolean = false
    private var previewScrollLine: Int = 0
    private var previewTotalLines: Int = 1
    private val formatButtons = mutableListOf<Pair<SkinButton, Component>>()
    private val tabRects = mutableListOf<TabSlot>()
    private var tabScrollLeftRect: SkinRect? = null
    private var tabScrollRightRect: SkinRect? = null

    private var activeModal: SkinModal? = null
    private var awaitingSaveResult: Boolean = false
    private var closeOnSaveSuccess: Boolean = false
    private var serverErrorMessage: Component? = null
    private var savedToastUntilMs: Long = 0L

    private var cachedValidation: NoteValidator.ValidationResult = NoteValidator.validateStructure(entry)
    private var cachedByteSize: Int = 0

    private val skin: GuiSkin get() = GuiSkinLoader.get("note_editor")

    private val resultListener: (Boolean, String, List<String>) -> Unit = { success, messageKey, args ->
        Minecraft.getInstance().execute {
            if (!awaitingSaveResult) return@execute
            val shouldClose = closeOnSaveSuccess
            awaitingSaveResult = false
            closeOnSaveSuccess = false
            updateSaveButtonState()

            if (success) {
                lastSavedSnapshot = pendingRawEntry ?: entry
                pendingRawEntry = null
                pendingSavedSnapshot = null
                serverErrorMessage = null
                if (shouldClose) {
                    closeToParent()
                } else {
                    savedToastUntilMs = System.currentTimeMillis() + 2500L
                }
            } else {
                pendingRawEntry = null
                pendingSavedSnapshot = null
                serverErrorMessage = Component.translatable(messageKey, *args.toTypedArray())
            }
        }
    }

    private data class TabSlot(val pageIdx: Int, val rect: SkinRect)

    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = false

    override fun init() {
        val s = skin
        guiW = s.layoutWidth
        guiH = s.layoutHeight
        guiLeft = (width - guiW) / 2
        guiTop = (height - guiH) / 2

        clearWidgets()
        formatButtons.clear()

        ClientNoteLibrary.removeResultListener(resultListener)
        ClientNoteLibrary.addResultListener(resultListener)

        val metaRect = r("btn_meta", 10, 8, 22, 18)
        metaBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + metaRect.x, guiTop + metaRect.y, metaRect.w, metaRect.h, Component.empty(), "button") {
                openModal(
                    EntryMetaModal(entry.icon, entry.color, onDismiss = {}) { newIcon, newColor ->
                        entry = entry.copy(icon = newIcon, color = newColor)
                        onEntryMutated()
                    }
                )
            }
        )

        val titleRect = r("title_field", 35, 9, 175, 16)
        titleField = addRenderableWidget(
            SkinTextField({ skin }, font, guiLeft + titleRect.x, guiTop + titleRect.y, titleRect.w, titleRect.h, Component.translatable("abadoned_greatwork.notes.field.title"), "dark_field")
        )
        titleField.setMaxLength(NoteValidator.MAX_TITLE_LENGTH)
        titleField.setHint(Component.translatable("abadoned_greatwork.notes.editor.title_hint"))
        titleField.value = entry.title
        titleField.setResponder { newTitle ->
            if (newTitle != entry.title) {
                entry = entry.copy(title = newTitle)
                onEntryMutated()
            }
        }

        val prevRect = r("btn_preview", 216, 9, 54, 16)
        previewBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + prevRect.x, guiTop + prevRect.y, prevRect.w, prevRect.h, Component.translatable("abadoned_greatwork.notes.btn.preview"), "button", "icon.eye") {
                val previewEntry = entry.copy(title = entry.title.ifBlank { I18n.get("abadoned_greatwork.notes.untitled") })
                NotePatchouliCompat.openDraftPreview(previewEntry, this, pageIndex)
            }
        )
        previewBtn.active = NotePatchouliCompat.isAvailable()

        val saveRect = r("btn_save", 274, 9, 60, 16)
        saveBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + saveRect.x, guiTop + saveRect.y, saveRect.w, saveRect.h, Component.translatable("abadoned_greatwork.notes.btn.save"), "button_accent") {
                triggerSave(closeAfterSuccess = true)
            }
        )
        updateSaveButtonState()

        val closeRect = r("btn_close", 338, 9, 48, 16)
        closeBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + closeRect.x, guiTop + closeRect.y, closeRect.w, closeRect.h, Component.translatable("abadoned_greatwork.notes.btn.close"), "button") {
                attemptClose()
            }
        )

        val fmtRect = r("format_bar", 210, 36, 168, 15)
        val fmtDefs = listOf(
            Triple("icon.fmt.bold", "abadoned_greatwork.notes.fmt.bold_tip") { textArea.wrapSelectionOrInsert("$(l)", "$()") },
            Triple("icon.fmt.italic", "abadoned_greatwork.notes.fmt.italic_tip") { textArea.wrapSelectionOrInsert("$(o)", "$()") },
            Triple("icon.fmt.thing", "abadoned_greatwork.notes.fmt.thing_tip") { textArea.wrapSelectionOrInsert("$(thing)", "$()") },
            Triple("icon.fmt.item", "abadoned_greatwork.notes.fmt.item_tip") { textArea.wrapSelectionOrInsert("$(item)", "$()") },
            Triple("icon.fmt.br", "abadoned_greatwork.notes.fmt.br_tip") { textArea.insertToken("$(br)") },
            Triple("icon.fmt.clear", "abadoned_greatwork.notes.fmt.clear_tip") { textArea.insertToken("$()") },
        )
        var fx = guiLeft + fmtRect.x
        for ((iconKey, tipKey, action) in fmtDefs) {
            val btn = addRenderableWidget(
                SkinButton({ skin }, fx, guiTop + fmtRect.y, 20, 15, Component.empty(), "paper_button", iconKey) {
                    if (currentPage().type != NotePageType.EMPTY && !previewTextMode) {
                        action()
                        focusTextAreaOnly()
                    }
                }
            )
            formatButtons += btn to Component.translatable(tipKey)
            fx += 20
        }
        previewTextBtn = addRenderableWidget(
            SkinButton({ skin }, guiLeft + fmtRect.x + 122, guiTop + fmtRect.y, 46, 15, Component.translatable("abadoned_greatwork.notes.btn.text_preview_view"), "paper_button", "icon.eye") {
                if (currentPage().type != NotePageType.EMPTY) {
                    previewTextMode = !previewTextMode
                    previewTextBtn.selected = previewTextMode
                    if (previewTextMode) {
                        textArea.focused = false
                    } else {
                        focusTextAreaOnly()
                    }
                    updateRightToolbarState()
                }
            }
        )

        val leftRect = r("left_page", 10, 30, 184, 154)
        pageCanvas = NotePageCanvas(
            skinSupplier = { skin },
            x = guiLeft + leftRect.x,
            y = guiTop + leftRect.y,
            w = leftRect.w,
            h = leftRect.h,
            pageSupplier = { currentPage() },
            pageConsumer = { updated -> updateCurrentPage(updated) },
            openModal = { modal -> openModal(modal) },
            textLineCountSupplier = { textArea.lineCount() },
            capacitySupplier = { NotePageCanvas.pageTextCapacityLines(currentPage(), pageIndex == 0) },
        )

        val areaRect = r("text_area", 210, 53, 168, 125)
        textArea = SkinTextArea(
            skinSupplier = { skin },
            x = guiLeft + areaRect.x,
            y = guiTop + areaRect.y,
            w = areaRect.w,
            h = areaRect.h,
            getter = { currentPage().text },
            setter = { newText ->
                val cur = currentPage()
                if (cur.text != newText) {
                    updateCurrentPage(cur.copy(text = newText))
                }
            },
            maxLength = NoteValidator.currentLimits().maxTextPerPage,
            paperSprite = null,
            textColorKey = "paper_text",
            defaultTextColor = 0xFF_2B1F16.toInt(),
        )

        val actRect = r("page_actions", 282, 188, 104, 18)
        val ax = guiLeft + actRect.x
        val ay = guiTop + actRect.y + 1
        addPageBtn = addRenderableWidget(SkinButton({ skin }, ax, ay, 24, 16, Component.empty(), "button", "icon.add") { addPage() })
        moveLeftBtn = addRenderableWidget(SkinButton({ skin }, ax + 26, ay, 24, 16, Component.empty(), "button", "icon.left") { movePage(-1) })
        moveRightBtn = addRenderableWidget(SkinButton({ skin }, ax + 52, ay, 24, 16, Component.empty(), "button", "icon.right") { movePage(1) })
        delPageBtn = addRenderableWidget(SkinButton({ skin }, ax + 78, ay, 24, 16, Component.empty(), "button_danger", "icon.delete") { deletePage() })

        refreshPageState(resetSubIndices = false)
        activeModal?.init(width, height)
    }

    override fun removed() {
        ClientNoteLibrary.removeResultListener(resultListener)
        activeModal?.onClose()
        activeModal = null
        NoteVisualHelpers.clearEntityCache()
        super.removed()
    }

    override fun tick() {
        activeModal?.tick()
    }

    private fun r(key: String, dx: Int, dy: Int, dw: Int, dh: Int): SkinRect =
        skin.region(key, SkinRect(dx, dy, dw, dh))

    private fun currentPage(): NotePage {
        pageIndex = pageIndex.coerceIn(0, (entry.pages.size - 1).coerceAtLeast(0))
        return entry.pages[pageIndex]
    }

    private fun updateCurrentPage(updated: NotePage) {
        val list = entry.pages.toMutableList()
        if (pageIndex in list.indices) {
            list[pageIndex] = updated
            entry = entry.copy(pages = list)
            textArea.capacityLines = NotePageCanvas.pageTextCapacityLines(updated, pageIndex == 0)
            onEntryMutated()
        }
    }

    private fun onEntryMutated() {
        serverErrorMessage = null
        cachedValidation = NoteValidator.validateStructure(entry)
        cachedByteSize = if (cachedValidation is NoteValidator.ValidationResult.Ok) {
            NoteValidator.estimateNbtBytes(entry.toTag())
        } else {
            0
        }
    }

    private fun updateRightToolbarState() {
        val isEmptyPage = currentPage().type == NotePageType.EMPTY
        for ((btn, _) in formatButtons) {
            btn.visible = !isEmptyPage
            btn.active = !isEmptyPage && !previewTextMode
        }
        previewTextBtn.visible = !isEmptyPage
        previewTextBtn.active = !isEmptyPage
        previewTextBtn.selected = previewTextMode
    }

    private fun refreshPageState(resetSubIndices: Boolean) {
        pageIndex = pageIndex.coerceIn(0, (entry.pages.size - 1).coerceAtLeast(0))
        val cur = currentPage()
        val isEmptyPage = cur.type == NotePageType.EMPTY
        previewScrollLine = 0
        if (isEmptyPage) {
            previewTextMode = false
        }
        updateRightToolbarState()
        textArea.capacityLines = NotePageCanvas.pageTextCapacityLines(cur, pageIndex == 0)
        textArea.resetCursor()
        if (isEmptyPage) textArea.focused = false

        val limits = NoteValidator.currentLimits()
        addPageBtn.active = entry.pages.size < limits.maxPagesPerEntry
        moveLeftBtn.active = pageIndex > 0
        moveRightBtn.active = pageIndex < entry.pages.lastIndex
        delPageBtn.active = entry.pages.size > 1

        pageCanvas.rebuild(resetSubIndices)
        ensureActiveTabVisible()
        onEntryMutated()
    }

    private fun selectPage(idx: Int) {
        val clamped = idx.coerceIn(0, (entry.pages.size - 1).coerceAtLeast(0))
        if (clamped == pageIndex) return
        pageIndex = clamped
        titleField.isFocused = false
        textArea.focused = false
        refreshPageState(resetSubIndices = true)
    }

    private fun addPage() {
        val limits = NoteValidator.currentLimits()
        if (entry.pages.size >= limits.maxPagesPerEntry) return
        val list = entry.pages.toMutableList()
        val insertAt = (pageIndex + 1).coerceIn(0, list.size)
        list.add(insertAt, NotePage())
        entry = entry.copy(pages = list)
        pageIndex = insertAt
        refreshPageState(resetSubIndices = true)
    }

    private fun movePage(delta: Int) {
        val target = pageIndex + delta
        if (target !in entry.pages.indices) return
        val list = entry.pages.toMutableList()
        val moved = list.removeAt(pageIndex)
        list.add(target, moved)
        entry = entry.copy(pages = list)
        pageIndex = target
        refreshPageState(resetSubIndices = false)
    }

    private fun deletePage() {
        if (entry.pages.size <= 1) return
        val list = entry.pages.toMutableList()
        list.removeAt(pageIndex)
        entry = entry.copy(pages = list)
        pageIndex = pageIndex.coerceIn(0, list.lastIndex)
        refreshPageState(resetSubIndices = true)
    }

    private fun ensureActiveTabVisible() {
        val maxShow = MAX_VISIBLE_TABS
        if (entry.pages.size <= maxShow) {
            tabScrollOffset = 0
            return
        }
        val window = maxShow - 2
        if (pageIndex < tabScrollOffset) tabScrollOffset = pageIndex
        if (pageIndex >= tabScrollOffset + window) tabScrollOffset = pageIndex - window + 1
        tabScrollOffset = tabScrollOffset.coerceIn(0, (entry.pages.size - window).coerceAtLeast(0))
    }

    private fun isDirty(): Boolean = entry.copy(time = 0L) != lastSavedSnapshot.copy(time = 0L)

    private fun updateSaveButtonState() {
        saveBtn.active = !awaitingSaveResult
        saveBtn.message = Component.translatable(
            if (awaitingSaveResult) "abadoned_greatwork.notes.btn.saving"
            else "abadoned_greatwork.notes.btn.save"
        )
    }

    private fun triggerSave(closeAfterSuccess: Boolean) {
        if (awaitingSaveResult) return
        val res = NoteValidator.validateStructure(entry)
        cachedValidation = res
        when (res) {
            is NoteValidator.ValidationResult.Err -> {
                serverErrorMessage = Component.translatable(res.messageKey, *res.args.toTypedArray())
                res.pageIndex?.let { idx -> if (idx != pageIndex) selectPage(idx) }
            }
            is NoteValidator.ValidationResult.Ok -> {
                val prepared = res.entry.copy(time = System.currentTimeMillis())
                pendingRawEntry = entry
                pendingSavedSnapshot = prepared
                awaitingSaveResult = true
                closeOnSaveSuccess = closeAfterSuccess
                serverErrorMessage = null
                updateSaveButtonState()
                MsgNoteUpsertC2S(prepared.toTag()).sendToServer()
            }
        }
    }

    private fun attemptClose() {
        if (awaitingSaveResult) return
        if (!isDirty()) {
            closeToParent()
            return
        }
        openModal(
            DiscardConfirmModal(
                onDismiss = {},
                onDiscardAndClose = { closeToParent() },
                onSaveAndClose = { triggerSave(closeAfterSuccess = true) },
            )
        )
    }

    private fun closeToParent() {
        minecraft?.setScreen(parentScreen ?: NoteLibraryScreen())
    }

    private fun openModal(modal: SkinModal) {
        activeModal?.onClose()
        titleField.isFocused = false
        textArea.focused = false
        pageCanvas.clearFocus()
        modal.setDismissHook {
            if (activeModal === modal) {
                activeModal = null
            }
        }
        modal.init(width, height)
        activeModal = modal
    }

    private fun focusTextAreaOnly() {
        titleField.isFocused = false
        pageCanvas.clearFocus()
        focused = null
        textArea.focused = true
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Rendered explicitly at the start of render() so super.render() does not blur twice or draw over the panel
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.renderBackground(graphics, mouseX, mouseY, partialTicks)
        val s = skin

        SkinRenderer.drawSprite(graphics, s, "panel", guiLeft, guiTop, guiW, guiH)
        SkinRenderer.drawSprite(graphics, s, "book_cover", guiLeft + 6, guiTop + 26, guiW - 12, 160)

        val lr = r("left_page", 10, 30, 184, 154)
        val sr = r("book_spine", 194, 30, 8, 154)
        val rr = r("right_page", 202, 30, 184, 154)
        SkinRenderer.drawSprite(graphics, s, "book_page_left", guiLeft + lr.x, guiTop + lr.y, lr.w, lr.h)
        SkinRenderer.drawSprite(graphics, s, "book_spine", guiLeft + sr.x, guiTop + sr.y, sr.w, sr.h)
        SkinRenderer.drawSprite(graphics, s, "book_page_right", guiLeft + rr.x, guiTop + rr.y, rr.w, rr.h)

        if (currentPage().type == NotePageType.EMPTY) {
            val hint = Component.translatable("abadoned_greatwork.notes.editor.empty_right_hint")
            val split = font.split(hint, rr.w - 30)
            var hy = guiTop + rr.y + 62
            for (line in split.take(3)) {
                graphics.drawString(font, line, guiLeft + rr.x + 15, hy, s.color("paper_text_dim", 0xFF_6B5844.toInt()), false)
                hy += 10
            }
        } else if (previewTextMode) {
            previewTotalLines = NoteBookTextPreview.renderPreview(
                graphics = graphics,
                font = font,
                raw = currentPage().text,
                x = textArea.x,
                y = textArea.y,
                w = textArea.w,
                h = textArea.h,
                scrollLine = previewScrollLine,
                capacityLines = textArea.capacityLines,
                skin = s,
            )
        } else {
            textArea.render(graphics)
        }

        val bgMouseX = if (activeModal == null) mouseX else -1
        val bgMouseY = if (activeModal == null) mouseY else -1

        renderPageTabs(graphics, s, bgMouseX, bgMouseY)
        renderStatusBar(graphics, s, bgMouseX, bgMouseY)

        super.render(graphics, bgMouseX, bgMouseY, partialTicks)

        // 绘制 meta 按钮上的条目图标与颜色下划线
        val iconStack = NoteVisualHelpers.resolveItemStack(entry.icon)
        graphics.renderItem(iconStack, metaBtn.x + 3, metaBtn.y + 1)
        val accentCol = GuiSkin.parseHexColor("FF${entry.color}") ?: 0xFF_9B59B6.toInt()
        graphics.fill(metaBtn.x + 3, metaBtn.y + metaBtn.height - 3, metaBtn.x + metaBtn.width - 3, metaBtn.y + metaBtn.height - 1, accentCol)

        pageCanvas.render(graphics, bgMouseX, bgMouseY, partialTicks)

        if (activeModal == null) {
            renderHoverTooltips(graphics, mouseX, mouseY)
        } else {
            activeModal!!.render(graphics, mouseX, mouseY, partialTicks)
        }
    }

    private fun renderPageTabs(graphics: GuiGraphics, s: GuiSkin, mouseX: Int, mouseY: Int) {
        val strip = r("page_strip", 10, 188, 268, 18)
        val sx = guiLeft + strip.x
        val sy = guiTop + strip.y + 1
        tabRects.clear()
        tabScrollLeftRect = null
        tabScrollRightRect = null

        val errPage = (cachedValidation as? NoteValidator.ValidationResult.Err)?.pageIndex
        val total = entry.pages.size
        val overflow = total > MAX_VISIBLE_TABS

        var curX = sx
        if (overflow) {
            val lRect = SkinRect(curX, sy, 14, 16)
            tabScrollLeftRect = lRect
            SkinRenderer.drawSprite(graphics, s, "button", lRect.x, lRect.y, lRect.w, lRect.h, if (tabScrollOffset > 0) "normal" else "disabled")
            SkinRenderer.drawSprite(graphics, s, "icon.left", lRect.x + 1, lRect.y + 2, 12, 12)
            curX += 16
        }

        val visibleCount = if (overflow) MAX_VISIBLE_TABS - 2 else total
        val start = if (overflow) tabScrollOffset else 0
        val end = minOf(total, start + visibleCount)

        for (idx in start until end) {
            val tabRect = SkinRect(curX, sy, 28, 16)
            tabRects += TabSlot(idx, tabRect)
            val state = when {
                errPage == idx -> "error"
                idx == pageIndex -> "selected"
                else -> "normal"
            }
            SkinRenderer.drawSprite(graphics, s, "page_tab", tabRect.x, tabRect.y, tabRect.w, tabRect.h, state)
            val col = s.color(if (idx == pageIndex) "title" else "text", 0xFF_F2EBF9.toInt())
            SkinRenderer.drawCenteredNoShadow(graphics, font, "${idx + 1}", tabRect.x + tabRect.w / 2, tabRect.y + 4, col)
            curX += 30
        }

        if (overflow) {
            val rRect = SkinRect(sx + strip.w - 14, sy, 14, 16)
            tabScrollRightRect = rRect
            val canRight = end < total
            SkinRenderer.drawSprite(graphics, s, "button", rRect.x, rRect.y, rRect.w, rRect.h, if (canRight) "normal" else "disabled")
            SkinRenderer.drawSprite(graphics, s, "icon.right", rRect.x + 1, rRect.y + 2, 12, 12)
        }
    }

    private fun renderStatusBar(graphics: GuiGraphics, s: GuiSkin, mouseX: Int, mouseY: Int) {
        val bar = r("status_bar", 10, 209, 376, 14)
        val bx = guiLeft + bar.x
        val by = guiTop + bar.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", bx, by, bar.w, bar.h)

        val err = cachedValidation as? NoteValidator.ValidationResult.Err
        val (msgComp, colorKey) = when {
            serverErrorMessage != null -> serverErrorMessage!! to "error"
            err != null -> {
                val base = Component.translatable(err.messageKey, *err.args.toTypedArray())
                val withJump = if (err.pageIndex != null) {
                    Component.translatable("abadoned_greatwork.notes.editor.err_jump", base, err.pageIndex + 1)
                } else {
                    base
                }
                withJump to "error"
            }
            awaitingSaveResult -> Component.translatable("abadoned_greatwork.notes.editor.saving_status") to "text"
            System.currentTimeMillis() < savedToastUntilMs -> Component.translatable("abadoned_greatwork.notes.editor.saved_toast") to "ok"
            cachedByteSize > 24_000 -> Component.translatable("abadoned_greatwork.notes.editor.size_warn", cachedByteSize / 1024, NoteValidator.MAX_SERIALIZED_BYTES / 1024) to "error"
            isDirty() -> Component.translatable("abadoned_greatwork.notes.editor.dirty_status", pageIndex + 1, entry.pages.size) to "text_dim"
            else -> Component.translatable("abadoned_greatwork.notes.editor.clean_status", pageIndex + 1, entry.pages.size) to "text_dim"
        }

        val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(msgComp, bar.w - 10))
        graphics.drawString(font, trimmed, bx + 5, by + 3, s.color(colorKey, 0xFF_A293B8.toInt()), false)
    }

    private fun renderHoverTooltips(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (metaBtn.isMouseOver(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.renderComponentTooltip(font, listOf(Component.translatable("abadoned_greatwork.notes.editor.meta_tip")), mouseX, mouseY)
            return
        }
        if (previewTextBtn.visible && previewTextBtn.isMouseOver(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.renderComponentTooltip(font, listOf(Component.translatable("abadoned_greatwork.notes.btn.text_preview_tip")), mouseX, mouseY)
            return
        }
        for ((btn, tip) in formatButtons) {
            if (btn.visible && btn.isMouseOver(mouseX.toDouble(), mouseY.toDouble())) {
                graphics.renderComponentTooltip(font, listOf(tip), mouseX, mouseY)
                return
            }
        }
        for (slot in tabRects) {
            val r = slot.rect
            if (mouseX >= r.x && mouseX < r.x + r.w && mouseY >= r.y && mouseY < r.y + r.h) {
                val p = entry.pages[slot.pageIdx]
                val pName = I18n.get(p.type.langKey)
                val label = if (p.title.isNotBlank()) "${slot.pageIdx + 1}. $pName - ${p.title}" else "${slot.pageIdx + 1}. $pName"
                graphics.renderComponentTooltip(font, listOf(Component.literal(label)), mouseX, mouseY)
                return
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        activeModal?.let { return it.mouseClicked(mouseX, mouseY, button) }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            if (titleField.isFocused) {
                textArea.focused = false
                pageCanvas.clearFocus()
            } else {
                titleField.isFocused = false
            }
            return true
        }

        if (currentPage().type != NotePageType.EMPTY && !previewTextMode && textArea.mouseClicked(mouseX, mouseY, button)) {
            titleField.isFocused = false
            pageCanvas.clearFocus()
            focused = null
            return true
        }

        if (pageCanvas.mouseClicked(mouseX, mouseY, button)) {
            titleField.isFocused = false
            textArea.focused = false
            focused = null
            return true
        }

        if (button == 0) {
            tabScrollLeftRect?.let { lr ->
                if (mouseX >= lr.x && mouseX < lr.x + lr.w && mouseY >= lr.y && mouseY < lr.y + lr.h) {
                    tabScrollOffset = (tabScrollOffset - 1).coerceAtLeast(0)
                    return true
                }
            }
            tabScrollRightRect?.let { rr ->
                if (mouseX >= rr.x && mouseX < rr.x + rr.w && mouseY >= rr.y && mouseY < rr.y + rr.h) {
                    val maxOff = (entry.pages.size - (MAX_VISIBLE_TABS - 2)).coerceAtLeast(0)
                    tabScrollOffset = (tabScrollOffset + 1).coerceAtMost(maxOff)
                    return true
                }
            }
            for (slot in tabRects) {
                val tr = slot.rect
                if (mouseX >= tr.x && mouseX < tr.x + tr.w && mouseY >= tr.y && mouseY < tr.y + tr.h) {
                    selectPage(slot.pageIdx)
                    return true
                }
            }
            val bar = r("status_bar", 10, 209, 376, 14)
            if (mouseX >= guiLeft + bar.x && mouseX < guiLeft + bar.x + bar.w && mouseY >= guiTop + bar.y && mouseY < guiTop + bar.y + bar.h) {
                val err = cachedValidation as? NoteValidator.ValidationResult.Err
                if (err?.pageIndex != null) {
                    selectPage(err.pageIndex)
                    return true
                }
            }
        }

        titleField.isFocused = false
        textArea.focused = false
        pageCanvas.clearFocus()
        return false
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        activeModal?.mouseMoved(mouseX, mouseY)
        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        activeModal?.let { return it.mouseDragged(mouseX, mouseY, button, dragX, dragY) }
        if (!previewTextMode && textArea.mouseDragged(mouseX, mouseY, button)) return true
        if (pageCanvas.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        activeModal?.let { return it.mouseReleased(mouseX, mouseY, button) }
        if (!previewTextMode && textArea.mouseReleased(button)) return true
        if (pageCanvas.mouseReleased(mouseX, mouseY, button)) return true
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        activeModal?.let { return it.mouseScrolled(mouseX, mouseY, scrollX, scrollY) }
        if (currentPage().type != NotePageType.EMPTY) {
            if (previewTextMode && textArea.isMouseOver(mouseX, mouseY)) {
                val visLines = ((textArea.h - 12) / NoteBookTextPreview.LINE_HEIGHT).coerceAtLeast(1)
                val maxScroll = (previewTotalLines - visLines).coerceAtLeast(0)
                previewScrollLine = (previewScrollLine - scrollY.toInt()).coerceIn(0, maxScroll)
                return true
            }
            if (!previewTextMode && textArea.mouseScrolled(mouseX, mouseY, scrollY)) return true
        }
        if (pageCanvas.mouseScrolled(mouseX, mouseY, scrollY)) return true
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        activeModal?.let { return it.keyPressed(keyCode, scanCode, modifiers) }

        if ((modifiers and GLFW.GLFW_MOD_CONTROL) != 0 && keyCode == GLFW.GLFW_KEY_S) {
            triggerSave(closeAfterSuccess = false)
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            attemptClose()
            return true
        }
        if (currentPage().type != NotePageType.EMPTY && !previewTextMode && textArea.focused && textArea.keyPressed(keyCode, scanCode, modifiers)) {
            return true
        }
        if (pageCanvas.hasFocusedTextInput() && pageCanvas.keyPressed(keyCode, scanCode, modifiers)) {
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        activeModal?.let { return it.charTyped(codePoint, modifiers) }
        if (currentPage().type != NotePageType.EMPTY && !previewTextMode && textArea.focused && textArea.charTyped(codePoint, modifiers)) {
            return true
        }
        if (pageCanvas.hasFocusedTextInput() && pageCanvas.charTyped(codePoint, modifiers)) {
            return true
        }
        return super.charTyped(codePoint, modifiers)
    }

    companion object {
        private const val MAX_VISIBLE_TABS = 9
    }
}
