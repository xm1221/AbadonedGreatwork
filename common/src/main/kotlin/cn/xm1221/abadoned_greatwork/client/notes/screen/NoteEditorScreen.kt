package cn.xm1221.abadoned_greatwork.client.notes.screen

import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRect
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinSuggestBox
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextArea
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.gui.skin.SuggestOption
import cn.xm1221.abadoned_greatwork.client.notes.NotePatchouliCompat
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteUpsertC2S
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NotePage
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component

/**
 * 贴图驱动的多书页手册条目编辑器：
 * - 支持全部 11 种 Patchouli / Hex Casting / 自定义 Iota 书页类型
 * - 右侧羊皮纸编辑区逻辑宽度固定 116px，与真实书页等宽所见即所得
 * - 支持直接调用真实 Patchouli GuiBookEntry 预览当前草稿
 */
class NoteEditorScreen(
    initialEntry: NoteEntry,
    private val parentScreen: Screen?,
) : Screen(Component.translatable("abadoned_greatwork.notes.editor_title")) {

    private val skin: GuiSkin get() = GuiSkinLoader.get("note_editor")

    private var entryId: String = initialEntry.id
    private var entryAuthor: String = initialEntry.author
    private var entryTitle: String = initialEntry.title
    private var entryIcon: String = initialEntry.icon
    private var entryColor: String = initialEntry.color
    private val pages: MutableList<NotePage> =
        initialEntry.pages.ifEmpty { listOf(NotePage(NotePageType.TEXT)) }.toMutableList()

    private var selectedPageIdx: Int = 0
    private var pageListScroll: Int = 0
    private var dirty: Boolean = false
    private var confirmDiscard: Boolean = false

    private var winX: Int = 0
    private var winY: Int = 0

    private lateinit var titleField: SkinTextField
    private lateinit var iconField: SkinTextField
    private lateinit var colorField: SkinTextField
    private lateinit var paperArea: SkinTextArea
    private lateinit var saveBtn: SkinButton
    private lateinit var cancelBtn: SkinButton
    private lateinit var pageUpBtn: SkinButton
    private lateinit var pageDownBtn: SkinButton
    private lateinit var pageDelBtn: SkinButton
    private lateinit var pageAddBtn: SkinButton

    private val dynamicWidgets = mutableListOf<AbstractWidget>()
    private val suggestBox = SkinSuggestBox { skin }
    private lateinit var formHost: NotePageFormHost

    override fun init() {
        val s = skin
        winX = ((width - s.layoutWidth) / 2).coerceAtLeast(0)
        winY = ((height - s.layoutHeight) / 2).coerceAtLeast(0)

        val topBar = s.region("top_bar", SkinRect(10, 8, 376, 20))
        val pageList = s.region("page_list", SkinRect(10, 32, 86, 164))
        val formPanel = s.region("form_panel", SkinRect(100, 32, 148, 164))
        val paperPanel = s.region("paper_panel", SkinRect(252, 32, 134, 164))
        val footerBar = s.region("footer_bar", SkinRect(10, 200, 376, 20))

        // Top Bar: Title, Icon (+ picker), Color
        val tx = winX + topBar.x
        val ty = winY + topBar.y + 2
        titleField = addRenderableWidget(
            SkinTextField({ skin }, font, tx, ty, 152, 16, Component.translatable("abadoned_greatwork.notes.title"))
        )
        titleField.setMaxLength(NoteValidator.MAX_TITLE_LENGTH)
        titleField.value = entryTitle
        titleField.setHint(Component.translatable("abadoned_greatwork.notes.title_hint"))
        titleField.setResponder {
            entryTitle = it
            markDirty()
        }

        // Icon preview frame at tx + 156 (18x16), then icon field + search btn
        val iconFieldX = tx + 178
        iconField = addRenderableWidget(
            SkinTextField({ skin }, font, iconFieldX, ty, 114, 16, Component.translatable("abadoned_greatwork.notes.field.icon"))
        )
        iconField.setMaxLength(128)
        iconField.value = entryIcon
        iconField.setResponder {
            entryIcon = it
            markDirty()
            if (suggestBox.open) suggestBox.updateFilter(it)
        }
        addRenderableWidget(
            SkinButton({ skin }, iconFieldX + 116, ty, 16, 16, Component.empty(), "button", "icon.search") {
                val options = BuiltInRegistries.ITEM.keySet().sortedBy { it.toString() }.map { rl ->
                    SuggestOption(rl.toString(), BuiltInRegistries.ITEM.get(rl).defaultInstance.hoverName.string)
                }
                suggestBox.show(iconFieldX - 20, ty + 18, 180, 110, options, iconField.value) { opt ->
                    iconField.value = opt.value
                    entryIcon = opt.value
                    markDirty()
                }
            }
        )

        val colorX = tx + 314
        colorField = addRenderableWidget(
            SkinTextField({ skin }, font, colorX, ty, 52, 16, Component.translatable("abadoned_greatwork.notes.field.color"))
        )
        colorField.setMaxLength(6)
        colorField.value = entryColor
        colorField.setResponder {
            entryColor = it
            markDirty()
        }

        // Page List Toolbar (bottom of page_list region)
        val plx = winX + pageList.x + 4
        val ply = winY + pageList.y + pageList.h - 20
        val btnW = 18
        pageAddBtn = addRenderableWidget(
            SkinButton({ skin }, plx, ply, btnW, 15, Component.empty(), "button_accent", "icon.add", Component.translatable("abadoned_greatwork.notes.btn.add_page")) {
                openAddPageTypeMenu(plx, (ply - 120).coerceAtLeast(8))
            }
        )
        pageUpBtn = addRenderableWidget(
            SkinButton({ skin }, plx + 20, ply, btnW, 15, Component.empty(), "button", "icon.up", Component.translatable("abadoned_greatwork.notes.btn.move_up")) {
                movePage(-1)
            }
        )
        pageDownBtn = addRenderableWidget(
            SkinButton({ skin }, plx + 40, ply, btnW, 15, Component.empty(), "button", "icon.down", Component.translatable("abadoned_greatwork.notes.btn.move_down")) {
                movePage(1)
            }
        )
        pageDelBtn = addRenderableWidget(
            SkinButton({ skin }, plx + 60, ply, btnW, 15, Component.empty(), "button_danger", "icon.delete", Component.translatable("abadoned_greatwork.notes.btn.del_page")) {
                deleteCurrentPage()
            }
        )

        // Paper Area (right panel) + quick format toolbar
        val px = winX + paperPanel.x
        val py = winY + paperPanel.y
        val tokens = listOf("BR" to "$(br)", "B" to "$(l)", "T" to "$(thing)", "I" to "$(item)", "CLR" to "$()")
        var tokX = px + 2
        val tokW = 24
        for ((lbl, code) in tokens) {
            addRenderableWidget(
                SkinButton({ skin }, tokX, py + 2, tokW, 13, Component.literal(lbl), "button") {
                    paperArea.insertToken(code)
                    paperArea.focused = true
                    setFocused(null)
                    markDirty()
                }
            )
            tokX += tokW + 2
        }

        paperArea = SkinTextArea(
            skinSupplier = { skin },
            x = px,
            y = py + 17,
            w = paperPanel.w,
            h = (paperPanel.h - 17).coerceAtLeast(48),
            getter = { currentPage().text },
            setter = { newText ->
                pages[selectedPageIdx] = currentPage().copy(text = newText)
                markDirty()
            },
            maxLength = NoteValidator.currentLimits().maxTextPerPage,
        )

        // Footer Bar
        val ftx = winX + footerBar.x
        val fty = winY + footerBar.y + 2
        val rightX = ftx + footerBar.w

        cancelBtn = addRenderableWidget(
            SkinButton({ skin }, rightX - 54, fty, 54, 16, Component.translatable("abadoned_greatwork.notes.btn.cancel"), "button") {
                handleCancel()
            }
        )
        saveBtn = addRenderableWidget(
            SkinButton({ skin }, rightX - 112, fty, 54, 16, Component.translatable("abadoned_greatwork.notes.btn.save"), "button_accent") {
                saveAndClose()
            }
        )
        addRenderableWidget(
            SkinButton({ skin }, rightX - 176, fty, 60, 16, Component.translatable("abadoned_greatwork.notes.btn.preview"), "button", "icon.eye") {
                NotePatchouliCompat.openDraftPreview(buildSnapshot(), this, selectedPageIdx)
            }.apply { active = NotePatchouliCompat.isAvailable() }
        )

        formHost = NotePageFormHost(
            skinSupplier = { skin },
            suggestBox = suggestBox,
            addWidget = { w ->
                dynamicWidgets.add(w)
                addRenderableWidget(w)
            },
            getPage = { currentPage() },
            setPage = { updated ->
                pages[selectedPageIdx] = updated
                markDirty()
            },
            rebuild = { rebuildDynamicForm() },
        )

        rebuildDynamicForm()
        updatePageButtons()
    }

    private fun currentPage(): NotePage {
        selectedPageIdx = selectedPageIdx.coerceIn(0, pages.lastIndex)
        return pages[selectedPageIdx]
    }

    private fun markDirty() {
        dirty = true
        confirmDiscard = false
        cancelBtn.message = Component.translatable("abadoned_greatwork.notes.btn.cancel")
    }

    private fun rebuildDynamicForm() {
        for (w in dynamicWidgets) {
            removeWidget(w)
        }
        dynamicWidgets.clear()

        val formRect = skin.region("form_panel", SkinRect(100, 32, 148, 164))
        val fx = winX + formRect.x
        val fy = winY + formRect.y

        // 顶部页类型切换按钮
        val cur = currentPage()
        val typeBtn = SkinButton(
            { skin },
            fx + 4,
            fy + 4,
            formRect.w - 8,
            15,
            Component.translatable(cur.type.langKey),
            "button",
            "icon.page.${NoteLibraryScreen.pageIconSuffix(cur.type)}",
        ) {
            openChangePageTypeMenu(fx + 4, fy + 20)
        }
        dynamicWidgets.add(typeBtn)
        addRenderableWidget(typeBtn)

        formHost.build(fx, fy, formRect.w, formRect.h)
        updatePageButtons()
    }

    private fun updatePageButtons() {
        val limits = NoteValidator.currentLimits()
        pageAddBtn.active = pages.size < limits.maxPagesPerEntry
        pageUpBtn.active = selectedPageIdx > 0
        pageDownBtn.active = selectedPageIdx < pages.lastIndex
        pageDelBtn.active = pages.size > 1
        saveBtn.active = validateSnapshot() is NoteValidator.ValidationResult.Ok
    }

    private fun openAddPageTypeMenu(sx: Int, sy: Int) {
        val options = NotePageType.entries.map { t ->
            SuggestOption(t.typeId, Component.translatable(t.langKey).string)
        }
        suggestBox.show(sx, sy, 150, 148, options, "") { chosen ->
            val type = NotePageType.fromTypeId(chosen.value) ?: return@show
            pages.add(selectedPageIdx + 1, defaultPageForType(type))
            selectedPageIdx++
            paperArea.resetCursor()
            markDirty()
            rebuildDynamicForm()
        }
    }

    private fun openChangePageTypeMenu(sx: Int, sy: Int) {
        val options = NotePageType.entries.map { t ->
            SuggestOption(t.typeId, Component.translatable(t.langKey).string)
        }
        suggestBox.show(sx, sy, 150, 148, options, "") { chosen ->
            val type = NotePageType.fromTypeId(chosen.value) ?: return@show
            val cur = currentPage()
            if (cur.type != type) {
                pages[selectedPageIdx] = defaultPageForType(type).copy(title = cur.title, text = cur.text)
                markDirty()
                rebuildDynamicForm()
            }
        }
    }

    private fun defaultPageForType(type: NotePageType): NotePage = when (type) {
        NotePageType.SPOTLIGHT -> NotePage(type, fields = mapOf("item" to "minecraft:amethyst_shard", "link_recipe" to "false"))
        NotePageType.HEX_MANUAL_PATTERN -> NotePage(type, patterns = listOf(cn.xm1221.abadoned_greatwork.notes.ManualPatternDef("EAST", "qaq")))
        NotePageType.IMAGE -> NotePage(type, images = listOf(""), fields = mapOf("border" to "true"))
        NotePageType.ENTITY -> NotePage(type, fields = mapOf("entity" to "minecraft:allay", "scale" to "1.0", "rotate" to "true"))
        NotePageType.LINK -> NotePage(type, fields = mapOf("url" to "https://", "link_text" to "Link"))
        else -> NotePage(type)
    }

    private fun movePage(delta: Int) {
        val target = selectedPageIdx + delta
        if (target !in pages.indices) return
        val moved = pages.removeAt(selectedPageIdx)
        pages.add(target, moved)
        selectedPageIdx = target
        markDirty()
        rebuildDynamicForm()
    }

    private fun deleteCurrentPage() {
        if (pages.size <= 1) return
        pages.removeAt(selectedPageIdx)
        selectedPageIdx = selectedPageIdx.coerceIn(0, pages.lastIndex)
        paperArea.resetCursor()
        markDirty()
        rebuildDynamicForm()
    }

    private fun buildSnapshot(): NoteEntry = NoteEntry(
        id = entryId,
        title = entryTitle,
        author = entryAuthor.ifBlank { minecraft?.player?.gameProfile?.name.orEmpty() },
        time = System.currentTimeMillis(),
        icon = entryIcon,
        color = entryColor,
        pages = pages.toList(),
    )

    private fun validateSnapshot(): NoteValidator.ValidationResult =
        NoteValidator.validateStructure(buildSnapshot())

    private fun saveAndClose() {
        val res = validateSnapshot()
        if (res is NoteValidator.ValidationResult.Ok) {
            MsgNoteUpsertC2S(res.entry.toTag()).sendToServer()
            dirty = false
            minecraft?.setScreen(parentScreen)
        }
    }

    private fun handleCancel() {
        if (dirty && !confirmDiscard) {
            confirmDiscard = true
            cancelBtn.message = Component.translatable("abadoned_greatwork.notes.btn.discard_confirm")
            return
        }
        minecraft?.setScreen(parentScreen)
    }

    override fun onClose() {
        handleCancel()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(graphics)
        val s = skin
        SkinRenderer.drawSprite(graphics, s, "panel", winX, winY, s.layoutWidth, s.layoutHeight)

        val topBar = s.region("top_bar", SkinRect(10, 8, 376, 20))
        val pageList = s.region("page_list", SkinRect(10, 32, 86, 164))
        val formPanel = s.region("form_panel", SkinRect(100, 32, 148, 164))
        val footerBar = s.region("footer_bar", SkinRect(10, 200, 376, 20))

        // Top Bar icon & color preview
        val tx = winX + topBar.x
        val ty = winY + topBar.y + 2
        SkinRenderer.drawSprite(graphics, s, "slot_frame", tx + 156, ty - 1, 18, 18)
        graphics.renderItem(NoteLibraryScreen.resolveIconStack(entryIcon), tx + 157, ty)

        val swatchColor = runCatching {
            val hex = entryColor.trim().removePrefix("#")
            if (hex.length == 6) (0xFF shl 24) or hex.toInt(16) else 0xFF_54398A.toInt()
        }.getOrDefault(0xFF_54398A.toInt())
        SkinRenderer.drawSprite(graphics, s, "slot_frame", tx + 368, ty, 10, 16)
        graphics.fill(tx + 370, ty + 2, tx + 376, ty + 14, swatchColor)

        // Page List Panel
        val plx = winX + pageList.x
        val ply = winY + pageList.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", plx, ply, pageList.w, pageList.h)

        val listAreaH = pageList.h - 24
        val rowH = 17
        val visibleRows = (listAreaH / rowH).coerceAtLeast(1)
        val maxScroll = (pages.size - visibleRows).coerceAtLeast(0)
        pageListScroll = pageListScroll.coerceIn(0, maxScroll)

        val end = minOf(pages.size, pageListScroll + visibleRows)
        for (i in pageListScroll until end) {
            val ry = ply + 4 + (i - pageListScroll) * rowH
            val rowW = pageList.w - (if (pages.size > visibleRows) 12 else 8)
            val rowSprite = if (i == selectedPageIdx) "list_item_selected" else "list_item_normal"
            SkinRenderer.drawSprite(graphics, s, rowSprite, plx + 4, ry, rowW, rowH - 1)
            val p = pages[i]
            SkinRenderer.drawSprite(graphics, s, "icon.page.${NoteLibraryScreen.pageIconSuffix(p.type)}", plx + 6, ry + 2, 12, 12)
            val rowLabel = "${i + 1}. ${p.type.shortLabel} ${p.title}"
            graphics.drawString(
                font,
                font.plainSubstrByWidth(rowLabel, rowW - 22),
                plx + 20,
                ry + 4,
                s.color("text", 0xFF_EDE4FA.toInt()),
                false,
            )
        }

        // Form Panel
        val fx = winX + formPanel.x
        val fy = winY + formPanel.y
        SkinRenderer.drawSprite(graphics, s, "sub_panel", fx, fy, formPanel.w, formPanel.h)

        // Paper Area (renders page_paper sprite + WYSIWYG text)
        if (currentPage().type == NotePageType.EMPTY) {
            SkinRenderer.drawSprite(graphics, s, "page_paper", paperArea.x, paperArea.y, paperArea.w, paperArea.h)
            graphics.drawWordWrap(
                font,
                Component.translatable("abadoned_greatwork.notes.empty_page_hint"),
                paperArea.x + 10,
                paperArea.y + 16,
                paperArea.w - 20,
                s.color("paper_text", 0xFF_2B2016.toInt()),
            )
        } else {
            paperArea.render(graphics)
        }

        // Footer validation status
        val valRes = validateSnapshot()
        saveBtn.active = valRes is NoteValidator.ValidationResult.Ok
        val ftx = winX + footerBar.x
        val fty = winY + footerBar.y + 6
        val maxStatusW = (footerBar.w - 182).coerceAtLeast(40)
        when (valRes) {
            is NoteValidator.ValidationResult.Ok -> {
                val okText = Component.translatable("abadoned_greatwork.notes.valid_ok", selectedPageIdx + 1, pages.size)
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(okText, maxStatusW)), ftx, fty, s.color("ok", 0xFF_78E092.toInt()), false)
            }
            is NoteValidator.ValidationResult.Err -> {
                val errText = Component.translatable(valRes.messageKey, *valRes.args.toTypedArray())
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(errText, maxStatusW)), ftx, fty, s.color("error", 0xFF_EC6270.toInt()), false)
            }
        }

        formHost.renderCustomOverlays(graphics, fx, fy, formPanel.w, formPanel.h)
        super.render(graphics, mouseX, mouseY, partialTicks)
        suggestBox.render(graphics, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (suggestBox.open && suggestBox.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        // Page list click
        val pageList = skin.region("page_list", SkinRect(10, 32, 86, 164))
        val plx = winX + pageList.x
        val ply = winY + pageList.y
        val listAreaH = pageList.h - 24
        if (button == 0 && mouseX >= plx + 4 && mouseX < plx + pageList.w - 6 && mouseY >= ply + 4 && mouseY < ply + listAreaH) {
            val rowH = 17
            val row = ((mouseY - (ply + 4)) / rowH).toInt()
            val idx = pageListScroll + row
            if (row >= 0 && idx in pages.indices && idx != selectedPageIdx) {
                selectedPageIdx = idx
                paperArea.resetCursor()
                paperArea.focused = false
                setFocused(null)
                rebuildDynamicForm()
                return true
            }
        }

        if (currentPage().type != NotePageType.EMPTY && paperArea.mouseClicked(mouseX, mouseY, button)) {
            setFocused(null)
            return true
        }
        paperArea.focused = false
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        if (suggestBox.open && suggestBox.mouseScrolled(mouseX, mouseY, delta)) {
            return true
        }
        if (currentPage().type != NotePageType.EMPTY && paperArea.mouseScrolled(mouseX, mouseY, delta)) {
            return true
        }
        val pageList = skin.region("page_list", SkinRect(10, 32, 86, 164))
        val plx = winX + pageList.x
        val ply = winY + pageList.y
        if (mouseX >= plx && mouseX < plx + pageList.w && mouseY >= ply && mouseY < ply + pageList.h) {
            val visibleRows = ((pageList.h - 24) / 17).coerceAtLeast(1)
            val maxScroll = (pages.size - visibleRows).coerceAtLeast(0)
            pageListScroll = (pageListScroll - delta.toInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, delta)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (paperArea.focused && getFocused() == null && currentPage().type != NotePageType.EMPTY) {
            if (paperArea.keyPressed(keyCode, scanCode, modifiers)) {
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (paperArea.focused && getFocused() == null && currentPage().type != NotePageType.EMPTY) {
            if (paperArea.charTyped(codePoint, modifiers)) {
                return true
            }
        }
        return super.charTyped(codePoint, modifiers)
    }
}
