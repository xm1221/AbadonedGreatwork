package cn.xm1221.abadoned_greatwork.client.ponder

import at.petrak.hexcasting.common.lib.HexSounds
import cn.xm1221.abadoned_greatwork.ponder.data.PonderIndexEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * 独立「问天」章节索引与搜索界面：
 * - 按分类标签浏览与实时关键字全文搜索（支持章节标题、知识点正文、动作名、图案签名与 ID）；
 * - 集成原 HexGuide 的《咒术笔记》全文与按图案搜索（点击匹配页可直达《咒术笔记》对应页）；
 * - 佩戴探知透镜凝视图案并按 `G` 时自动进入图案检索结果视图。
 */
class HexPonderIndexScreen(
    private val parentScreen: Screen? = null,
    initialPattern: PonderClientState.PatternSearchQuery? = null,
) : Screen(Component.translatable("hexponder.index.title")) {

    companion object {
        private const val MANUAL_TAB_ID = "__manual__"
    }

    private var selectedCategoryId: String? = null
    private var activePattern: PonderClientState.PatternSearchQuery? = initialPattern
    private var scrollRows: Int = 0
    private var searchQuery: String = ""
    private var searchBox: EditBox? = null

    private var winX = 0
    private var winY = 0
    private var winW = 0
    private var winH = 0
    private var listX = 0
    private var listY = 0
    private var listW = 0
    private var listH = 0
    private var clearFilterRect: IntArray? = null

    private data class ClickableTab(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val catId: String?,
        val label: String,
        val icon: String,
    )

    private sealed interface DisplayRow {
        data class SectionHeader(val text: String, val accent: Int) : DisplayRow
        data class ScenePair(val left: PonderIndexEntry, val right: PonderIndexEntry?) : DisplayRow
        data class ManualHitRow(val hit: PonderPatchouliCompat.ManualHit) : DisplayRow
    }

    private sealed interface ClickableTarget {
        val x: Int
        val y: Int
        val w: Int
        val h: Int
        fun contains(mx: Double, my: Double): Boolean = mx >= x && mx < x + w && my >= y && my < y + h

        data class Scene(
            override val x: Int,
            override val y: Int,
            override val w: Int,
            override val h: Int,
            val entry: PonderIndexEntry,
        ) : ClickableTarget

        data class Manual(
            override val x: Int,
            override val y: Int,
            override val w: Int,
            override val h: Int,
            val hit: PonderPatchouliCompat.ManualHit,
        ) : ClickableTarget
    }

    private val categoryTabs = ArrayList<ClickableTab>()
    private val clickableTargets = ArrayList<ClickableTarget>()

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        super.init()
        winW = (width - 24).coerceIn(290, 460)
        winH = (height - 22).coerceIn(195, 320)
        winX = (width - winW) / 2
        winY = (height - winH) / 2

        val searchW = (winW * 0.43f).roundToInt().coerceIn(105, 190)
        val box = EditBox(font, winX + winW - 28 - searchW, winY + 5, searchW, 14, Component.translatable("hexponder.index.search"))
        box.value = searchQuery
        box.setHint(Component.translatable("hexponder.index.search"))
        box.setResponder { value ->
            searchQuery = value
            scrollRows = 0
        }
        addRenderableWidget(box)
        searchBox = box

        listX = winX + 8
        listY = winY + if (activePattern != null) 66 else 46
        listW = winW - 16
        listH = winY + winH - 8 - listY

        rebuildTabs()
    }

    private fun refreshListBounds() {
        listY = winY + if (activePattern != null) 66 else 46
        listH = (winY + winH - 8 - listY).coerceAtLeast(60)
    }

    private fun rebuildTabs() {
        categoryTabs.clear()
        val y = winY + 24
        var x = winX + 8
        val allLabel = localize("hexponder.category.all")
        val allW = font.width(allLabel) + 10
        categoryTabs.add(ClickableTab(x, y, allW, 18, null, allLabel, ""))
        x += allW + 2

        if (PonderPatchouliCompat.isAvailable()) {
            val mLabel = localize("hexponder.index.manual_tab")
            val mw = font.width(mLabel) + 22
            if (x + mw <= winX + winW - 8) {
                categoryTabs.add(ClickableTab(x, y, mw, 18, MANUAL_TAB_ID, mLabel, "hexcasting:book"))
                x += mw + 2
            }
        }

        for (cat in PonderClientState.categories()) {
            val lbl = localize(cat.title)
            val tw = font.width(lbl) + 22
            if (x + tw > winX + winW - 8) break
            categoryTabs.add(ClickableTab(x, y, tw, 18, cat.id, lbl, cat.iconItem))
            x += tw + 2
        }
    }

    private fun sceneMatchesQuery(entry: PonderIndexEntry, qLower: String): Boolean {
        if (qLower.isEmpty()) return true
        if (localize(entry.title).lowercase().contains(qLower)) return true
        if (localize(entry.subtitle).lowercase().contains(qLower)) return true
        if (entry.id.toString().lowercase().contains(qLower)) return true
        if (entry.actionIds.any { it.lowercase().contains(qLower) || localize("hexcasting.action.$it").lowercase().contains(qLower) }) {
            return true
        }
        if (entry.triggers.actions.any { it.lowercase().contains(qLower) || localize("hexcasting.action.$it").lowercase().contains(qLower) }) {
            return true
        }
        if (entry.patternSigs.any { it.lowercase().contains(qLower) }) return true
        if (entry.triggers.items.any { it.lowercase().contains(qLower) }) return true
        if (entry.searchTerms.any { localize(it).lowercase().contains(qLower) || it.lowercase().contains(qLower) }) {
            return true
        }
        return false
    }

    private fun sceneMatchesPattern(entry: PonderIndexEntry, pat: PonderClientState.PatternSearchQuery): Boolean {
        val actStr = pat.actionId?.toString()
        if (actStr != null && (entry.actionIds.contains(actStr) || entry.triggers.actions.contains(actStr))) {
            return true
        }
        if (pat.sig.isNotEmpty() && entry.patternSigs.contains(pat.sig)) {
            return true
        }
        return false
    }

    private fun buildDisplayRows(): List<DisplayRow> {
        val qLower = searchQuery.trim().lowercase()
        val pat = activePattern
        val manualOnly = selectedCategoryId == MANUAL_TAB_ID

        val rows = ArrayList<DisplayRow>()

        if (pat != null) {
            if (!manualOnly) {
                val baseScenes = PonderClientState.entriesForCategory(selectedCategoryId)
                val matchedScenes = baseScenes
                    .filter { sceneMatchesPattern(it, pat) && sceneMatchesQuery(it, qLower) }
                if (matchedScenes.isNotEmpty()) {
                    rows.add(DisplayRow.SectionHeader(localize("hexponder.index.section_scenes"), PonderGuiDraw.TEXT_CYAN))
                    appendScenePairs(rows, matchedScenes)
                }
            }
            if (PonderPatchouliCompat.isAvailable()) {
                val manualHits = PonderPatchouliCompat.searchByPattern(pat.sig, pat.actionId)
                    .filter { qLower.isEmpty() || it.searchText.contains(qLower) }
                if (manualHits.isNotEmpty()) {
                    rows.add(DisplayRow.SectionHeader(localize("hexponder.index.section_manual"), PonderGuiDraw.TEXT_GOLD))
                    for (h in manualHits) rows.add(DisplayRow.ManualHitRow(h))
                }
            }
            return rows
        }

        if (manualOnly) {
            val hits = PonderPatchouliCompat.searchManual(searchQuery)
            for (h in hits) rows.add(DisplayRow.ManualHitRow(h))
            return rows
        }

        val matchedScenes = PonderClientState.entriesForCategory(selectedCategoryId)
            .filter { sceneMatchesQuery(it, qLower) }
        appendScenePairs(rows, matchedScenes)

        // 全局搜索关键字时，同时补充《咒术笔记》匹配页（若 Patchouli 已安装）
        if (selectedCategoryId == null && qLower.isNotEmpty() && PonderPatchouliCompat.isAvailable()) {
            val manualHits = PonderPatchouliCompat.searchManual(searchQuery)
            if (manualHits.isNotEmpty()) {
                rows.add(DisplayRow.SectionHeader(localize("hexponder.index.section_manual"), PonderGuiDraw.TEXT_GOLD))
                for (h in manualHits.take(40)) {
                    rows.add(DisplayRow.ManualHitRow(h))
                }
            }
        }
        return rows
    }

    private fun appendScenePairs(out: MutableList<DisplayRow>, list: List<PonderIndexEntry>) {
        var i = 0
        while (i < list.size) {
            val left = list[i]
            val right = if (i + 1 < list.size) list[i + 1] else null
            out.add(DisplayRow.ScenePair(left, right))
            i += 2
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, 0xCC09070F.toInt())

        PonderGuiDraw.drawBeveledPanel(g, winX, winY, winW, winH)
        g.drawString(font, localize("hexponder.index.title"), winX + 8, winY + 8, PonderGuiDraw.TEXT_GOLD, true)

        // 关闭按钮
        val closeX = winX + winW - 22
        val closeY = winY + 5
        val closeHover = mouseX >= closeX && mouseX < closeX + 16 && mouseY >= closeY && mouseY < closeY + 14
        PonderGuiDraw.drawPixelButton(g, font, closeX, closeY, 16, 14, "✕", closeHover)

        // 分类与《咒术笔记》标签
        for (tab in categoryTabs) {
            val hover = mouseX >= tab.x && mouseX < tab.x + tab.w && mouseY >= tab.y && mouseY < tab.y + tab.h
            val active = selectedCategoryId == tab.catId
            PonderGuiDraw.drawPixelButton(g, font, tab.x, tab.y, tab.w, tab.h, "", hover, active)
            if (tab.icon.isNotEmpty()) {
                val stack = PonderGuiDraw.resolveItemStack(tab.icon)
                if (!stack.isEmpty) {
                    g.renderItem(stack, tab.x + 3, tab.y + 1)
                }
                g.drawString(font, tab.label, tab.x + 21, tab.y + 5, if (active) PonderGuiDraw.TEXT_GOLD else PonderGuiDraw.TEXT_MAIN, true)
            } else {
                val tx = tab.x + (tab.w - font.width(tab.label)) / 2
                g.drawString(font, tab.label, tx, tab.y + 5, if (active) PonderGuiDraw.TEXT_GOLD else PonderGuiDraw.TEXT_MAIN, true)
            }
        }

        // 探知透镜按图案检索筛选条
        clearFilterRect = null
        val pat = activePattern
        if (pat != null) {
            val fy = winY + 45
            val fw = winW - 16
            PonderGuiDraw.drawRecessedBox(g, listX, fy, fw, 17, 0xFF1A1429.toInt(), PonderGuiDraw.AMETHYST_DARK)
            val actName = if (pat.actionId != null) {
                val key = "hexcasting.action.${pat.actionId}"
                if (I18n.exists(key)) I18n.get(key) else pat.actionId.toString()
            } else {
                localize("hexponder.index.unnamed_pattern")
            }
            val filterTxt = I18n.get("hexponder.index.pattern_filter", actName, pat.sig)
            val clearLbl = localize("hexponder.index.clear_filter")
            val cw = font.width(clearLbl) + 10
            val cx = listX + fw - cw - 2
            val cy = fy + 2
            clearFilterRect = intArrayOf(cx, cy, cw, 13)
            val cHover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + 13
            PonderGuiDraw.drawPixelButton(g, font, cx, cy, cw, 13, clearLbl, cHover)
            g.drawString(font, font.plainSubstrByWidth(filterTxt, (fw - cw - 12).coerceAtLeast(40)), listX + 5, fy + 5, PonderGuiDraw.AMETHYST_BRIGHT, true)
        }

        // 内容区凹框
        refreshListBounds()
        PonderGuiDraw.drawRecessedBox(g, listX, listY, listW, listH)

        val rows = buildDisplayRows()
        clickableTargets.clear()

        g.enableScissor(listX + 2, listY + 2, listX + listW - 2, listY + listH - 2)
        if (rows.isEmpty()) {
            val emptyTxt = localize("hexponder.index.empty")
            g.drawString(font, emptyTxt, listX + (listW - font.width(emptyTxt)) / 2, listY + listH / 2 - 4, PonderGuiDraw.TEXT_MUTED, false)
        } else {
            val rowUnitH = 35
            val maxVisibleUnits = ((listH - 8) / rowUnitH).coerceAtLeast(1)
            scrollRows = scrollRows.coerceIn(0, (rows.size - maxVisibleUnits).coerceAtLeast(0))

            var curY = listY + 5
            val gap = 4
            val cardW = (listW - 12 - gap) / 2
            val fullW = listW - 12

            for (i in scrollRows until rows.size) {
                if (curY + 14 > listY + listH - 4) break
                when (val row = rows[i]) {
                    is DisplayRow.SectionHeader -> {
                        g.drawString(font, row.text, listX + 6, curY + 3, row.accent, true)
                        g.fill(listX + 6, curY + 13, listX + listW - 6, curY + 14, 0x44FFFFFF)
                        curY += 16
                    }
                    is DisplayRow.ScenePair -> {
                        if (curY + 30 > listY + listH - 2) break
                        renderSceneCard(g, listX + 6, curY, cardW, 30, row.left, mouseX, mouseY)
                        if (row.right != null) {
                            renderSceneCard(g, listX + 6 + cardW + gap, curY, cardW, 30, row.right, mouseX, mouseY)
                        }
                        curY += 34
                    }
                    is DisplayRow.ManualHitRow -> {
                        if (curY + 28 > listY + listH - 2) break
                        renderManualCard(g, listX + 6, curY, fullW, 28, row.hit, mouseX, mouseY)
                        curY += 31
                    }
                }
            }
        }
        g.disableScissor()

        super.render(g, mouseX, mouseY, partialTick)
    }

    private fun renderSceneCard(
        g: GuiGraphics,
        cx: Int,
        cy: Int,
        cardW: Int,
        cardH: Int,
        entry: PonderIndexEntry,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH &&
            mouseY >= listY && mouseY < listY + listH
        PonderGuiDraw.drawPixelButton(g, font, cx, cy, cardW, cardH, "", hovered)
        val icon = PonderGuiDraw.resolveItemStack(entry.iconItem)
        if (!icon.isEmpty) {
            g.renderItem(icon, cx + 5, cy + 7)
        }
        val textX = cx + 25
        val availW = (cardW - 30).coerceAtLeast(20)
        val title = font.plainSubstrByWidth(localize(entry.title), availW)
        g.drawString(font, title, textX, cy + 6, PonderGuiDraw.TEXT_MAIN, true)

        val stepInfo = "${entry.stepCount} ${localize("hexponder.index.steps")}"
        g.drawString(font, stepInfo, textX, cy + 17, PonderGuiDraw.TEXT_MUTED, false)

        clickableTargets.add(ClickableTarget.Scene(cx, cy, cardW, cardH, entry))
    }

    private fun renderManualCard(
        g: GuiGraphics,
        cx: Int,
        cy: Int,
        cardW: Int,
        cardH: Int,
        hit: PonderPatchouliCompat.ManualHit,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH &&
            mouseY >= listY && mouseY < listY + listH
        PonderGuiDraw.drawPixelButton(g, font, cx, cy, cardW, cardH, "", hovered)
        val bookIcon = PonderGuiDraw.resolveItemStack("hexcasting:book")
        if (!bookIcon.isEmpty) {
            g.renderItem(bookIcon, cx + 4, cy + 6)
        }
        val textX = cx + 24
        val pageBadge = "[P.${hit.pageIndex + 1}]"
        val badgeW = font.width(pageBadge)
        val titleW = (cardW - 30 - badgeW - 6).coerceAtLeast(30)
        val title = font.plainSubstrByWidth(hit.entryName, titleW)
        g.drawString(font, title, textX, cy + 4, PonderGuiDraw.TEXT_GOLD, true)
        g.drawString(font, pageBadge, cx + cardW - 6 - badgeW, cy + 4, PonderGuiDraw.AMETHYST_BRIGHT, false)

        val snippetAvail = (cardW - 30).coerceAtLeast(30)
        val snip = if (hit.snippet.isNotEmpty()) hit.snippet else (hit.opId ?: hit.entryId.toString())
        g.drawString(font, font.plainSubstrByWidth(snip, snippetAvail), textX, cy + 15, PonderGuiDraw.TEXT_BODY, false)

        clickableTargets.add(ClickableTarget.Manual(cx, cy, cardW, cardH, hit))
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val closeX = winX + winW - 22
        val closeY = winY + 5
        if (mouseX >= closeX && mouseX < closeX + 16 && mouseY >= closeY && mouseY < closeY + 14) {
            onClose()
            return true
        }
        clearFilterRect?.let { r ->
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                playClick()
                activePattern = null
                scrollRows = 0
                refreshListBounds()
                return true
            }
        }
        for (tab in categoryTabs) {
            if (mouseX >= tab.x && mouseX < tab.x + tab.w && mouseY >= tab.y && mouseY < tab.y + tab.h) {
                playClick()
                selectedCategoryId = tab.catId
                scrollRows = 0
                return true
            }
        }
        for (target in clickableTargets) {
            if (target.contains(mouseX, mouseY)) {
                playClick()
                when (target) {
                    is ClickableTarget.Scene -> PonderClientState.openScene(target.entry.id, this)
                    is ClickableTarget.Manual -> PonderPatchouliCompat.openManualPage(target.hit.entryId, target.hit.pageIndex)
                }
                return true
            }
        }
        val box = searchBox
        if (box != null && !box.isMouseOver(mouseX, mouseY)) {
            focused = null
            box.isFocused = false
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        scrollRows = (scrollRows + if (delta > 0) -1 else 1).coerceAtLeast(0)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parentScreen)
    }

    private fun playClick() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(HexSounds.ABACUS, 1.0f))
    }

    private fun localize(keyOrText: String): String =
        if (I18n.exists(keyOrText)) I18n.get(keyOrText) else keyOrText
}
