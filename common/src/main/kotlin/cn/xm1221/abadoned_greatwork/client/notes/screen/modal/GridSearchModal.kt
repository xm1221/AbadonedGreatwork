package cn.xm1221.abadoned_greatwork.client.notes.screen.modal

import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.client.gui.skin.BaseSkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.notes.NoteFileIO
import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteVisualHelpers
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeType
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.charset.StandardCharsets

/**
 * 带自动聚焦搜索框、可拖滚动条与键盘导航的通用模态选择器基类。
 */
abstract class GridSearchModal<T>(
    title: Component,
    private val cols: Int,
    private val cellW: Int,
    private val cellH: Int,
    onDismiss: () -> Unit,
    protected val onPick: (T) -> Unit,
) : BaseSkinModal(title, "grid_modal", 288, 196, onDismiss) {

    protected lateinit var searchField: SkinTextField
    protected var query: String = ""
    protected var allEntries: List<T> = emptyList()
    protected var filteredEntries: List<T> = emptyList()
    protected var selectedIdx: Int = 0
    protected var scrollRows: Int = 0
    private var draggingScrollbar: Boolean = false
    private var dragOffsetY: Int = 0
    private var lastHoveredItem: T? = null

    protected open val gridTopOffset: Int = 46
    protected open val gridRightReserve: Int = 0

    protected val gridX: Int get() = winX + 10
    protected val gridY: Int get() = winY + gridTopOffset
    protected val gridW: Int get() = winW - 20 - gridRightReserve
    protected val gridH: Int get() = winH - gridTopOffset - 12
    protected val visibleRows: Int get() = ((gridH - 6) / cellH).coerceAtLeast(1)
    protected val totalRows: Int get() = ((filteredEntries.size + cols - 1) / cols).coerceAtLeast(1)

    protected abstract fun loadAllEntries(): List<T>
    protected abstract fun matchesQuery(item: T, q: String): Boolean
    protected abstract fun renderCell(graphics: GuiGraphics, item: T, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean)
    protected open fun tooltipFor(item: T): List<Component> = emptyList()

    override fun buildContent() {
        val mc = Minecraft.getInstance()
        val searchW = (winW - 20 - extraSearchRightReserve()).coerceAtLeast(80)
        searchField = addWidget(
            SkinTextField({ skin }, mc.font, winX + 10, winY + 25, searchW, 16, Component.translatable("abadoned_greatwork.notes.search_hint"), "dark_field")
        )
        searchField.setMaxLength(128)
        searchField.setHint(Component.translatable("abadoned_greatwork.notes.search_hint"))
        searchField.value = query
        searchField.setResponder { q ->
            query = q
            applyFilter()
        }
        buildExtraHeaderWidgets()

        allEntries = loadAllEntries()
        applyFilter()
        setModalFocus(searchField)
    }

    protected open fun extraSearchRightReserve(): Int = 0
    protected open fun buildExtraHeaderWidgets() {}

    protected fun reloadEntries() {
        allEntries = loadAllEntries()
        applyFilter()
    }

    protected fun applyFilter() {
        val q = query.trim().lowercase()
        filteredEntries = if (q.isEmpty()) allEntries else allEntries.filter { matchesQuery(it, q) }
        selectedIdx = if (filteredEntries.isEmpty()) -1 else 0
        scrollRows = 0
    }

    private fun ensureSelectedVisible() {
        if (selectedIdx < 0) return
        val row = selectedIdx / cols
        val maxScroll = (totalRows - visibleRows).coerceAtLeast(0)
        if (row < scrollRows) scrollRows = row
        if (row >= scrollRows + visibleRows) scrollRows = row - visibleRows + 1
        scrollRows = scrollRows.coerceIn(0, maxScroll)
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val s = skin
        val mc = Minecraft.getInstance()
        SkinRenderer.drawSprite(graphics, s, "sub_panel", gridX, gridY, gridW, gridH)

        val maxScroll = (totalRows - visibleRows).coerceAtLeast(0)
        scrollRows = scrollRows.coerceIn(0, maxScroll)

        var hoveredItem: T? = null
        if (filteredEntries.isEmpty()) {
            graphics.drawString(
                mc.font,
                Component.translatable("abadoned_greatwork.notes.no_matches"),
                gridX + 8,
                gridY + 10,
                s.color("text_dim", 0xFF_A293B8.toInt()),
                false,
            )
        } else {
            val startIdx = scrollRows * cols
            val endIdx = minOf(filteredEntries.size, startIdx + visibleRows * cols)
            val contentLeft = gridX + 4
            val contentTop = gridY + 4
            for (idx in startIdx until endIdx) {
                val rel = idx - startIdx
                val r = rel / cols
                val c = rel % cols
                val cx = contentLeft + c * cellW
                val cy = contentTop + r * cellH
                val hovered = mouseX >= cx && mouseX < cx + cellW - 2 && mouseY >= cy && mouseY < cy + cellH - 2
                val item = filteredEntries[idx]
                if (hovered) hoveredItem = item
                renderCell(graphics, item, cx, cy, cellW - 2, cellH - 2, hovered, idx == selectedIdx)
            }
        }

        if (totalRows > visibleRows) {
            val sbX = gridX + gridW - 7
            val sbY = gridY + 4
            val sbH = (gridH - 8).coerceAtLeast(16)
            SkinRenderer.drawSprite(graphics, s, "scrollbar_track", sbX, sbY, 4, sbH)
            val thumbH = maxOf(10, sbH * visibleRows / totalRows)
            val thumbY = sbY + ((sbH - thumbH) * scrollRows / maxScroll.coerceAtLeast(1))
            SkinRenderer.drawSprite(graphics, s, "scrollbar_thumb", sbX, thumbY, 4, thumbH)
        }

        renderSidePreview(graphics, mouseX, mouseY, hoveredItem ?: filteredEntries.getOrNull(selectedIdx))
        lastHoveredItem = hoveredItem
    }

    override fun renderOverlay(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val mc = Minecraft.getInstance()
        lastHoveredItem?.let { item ->
            val tips = tooltipFor(item)
            if (tips.isNotEmpty()) {
                graphics.renderComponentTooltip(mc.font, tips, mouseX, mouseY)
            }
        }
    }

    protected open fun renderSidePreview(graphics: GuiGraphics, mouseX: Int, mouseY: Int, activeItem: T?) {}

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX < winX || mouseX >= winX + winW || mouseY < winY || mouseY >= winY + winH) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        // 优先检查网格内滚动条与网格单元
        if (button == 0 && mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            val maxScroll = (totalRows - visibleRows).coerceAtLeast(0)
            val sbX = gridX + gridW - 8
            if (maxScroll > 0 && mouseX >= sbX) {
                val sbY = gridY + 4
                val sbH = (gridH - 8).coerceAtLeast(16)
                val thumbH = maxOf(10, sbH * visibleRows / totalRows)
                val thumbY = sbY + ((sbH - thumbH) * scrollRows / maxScroll)
                if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                    draggingScrollbar = true
                    dragOffsetY = (mouseY - thumbY).toInt()
                } else {
                    val frac = ((mouseY - sbY - thumbH / 2.0) / (sbH - thumbH).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    scrollRows = (frac * maxScroll).toInt().coerceIn(0, maxScroll)
                    draggingScrollbar = true
                    dragOffsetY = thumbH / 2
                }
                return true
            }
            val col = ((mouseX - (gridX + 4)) / cellW).toInt()
            val row = ((mouseY - (gridY + 4)) / cellH).toInt()
            if (col in 0 until cols && row in 0 until visibleRows) {
                val idx = (scrollRows + row) * cols + col
                if (idx in filteredEntries.indices) {
                    selectedIdx = idx
                    val chosen = filteredEntries[idx]
                    dismiss()
                    onPick(chosen)
                    return true
                }
            }
        }
        val handled = super.mouseClicked(mouseX, mouseY, button)
        // 如果点击了网格空白，仍保持搜索框可继续打字
        if (focusedWidget == null) {
            setModalFocus(searchField)
        }
        return handled
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (button == 0 && draggingScrollbar) {
            val maxScroll = (totalRows - visibleRows).coerceAtLeast(1)
            val sbY = gridY + 4
            val sbH = (gridH - 8).coerceAtLeast(16)
            val thumbH = maxOf(10, sbH * visibleRows / totalRows)
            val span = (sbH - thumbH).coerceAtLeast(1)
            val frac = ((mouseY - sbY - dragOffsetY) / span.toDouble()).coerceIn(0.0, 1.0)
            scrollRows = (frac * maxScroll).toInt().coerceIn(0, maxScroll)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val maxScroll = (totalRows - visibleRows).coerceAtLeast(0)
        scrollRows = (scrollRows - scrollY.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dismiss()
            return true
        }
        if (filteredEntries.isNotEmpty()) {
            when (keyCode) {
                GLFW.GLFW_KEY_DOWN -> {
                    selectedIdx = (selectedIdx + cols).coerceIn(0, filteredEntries.lastIndex)
                    ensureSelectedVisible()
                    return true
                }
                GLFW.GLFW_KEY_UP -> {
                    selectedIdx = (selectedIdx - cols).coerceIn(0, filteredEntries.lastIndex)
                    ensureSelectedVisible()
                    return true
                }
                GLFW.GLFW_KEY_RIGHT -> if (cols > 1 && (!searchField.isFocused || searchField.value.isEmpty())) {
                    selectedIdx = (selectedIdx + 1).coerceIn(0, filteredEntries.lastIndex)
                    ensureSelectedVisible()
                    return true
                }
                GLFW.GLFW_KEY_LEFT -> if (cols > 1 && (!searchField.isFocused || searchField.value.isEmpty())) {
                    selectedIdx = (selectedIdx - 1).coerceIn(0, filteredEntries.lastIndex)
                    ensureSelectedVisible()
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    val chosen = filteredEntries.getOrNull(selectedIdx)
                    if (chosen != null) {
                        dismiss()
                        onPick(chosen)
                        return true
                    }
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }
}

// ==================== 6 种具体可视化选择器 ====================

data class ItemChoice(val id: String, val name: String, val stack: ItemStack)

class ItemPickerModal(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) : GridSearchModal<ItemChoice>(
    title = Component.translatable("abadoned_greatwork.notes.picker.item"),
    cols = 10,
    cellW = 26,
    cellH = 26,
    onDismiss = onDismiss,
    onPick = { onPick(it.id) },
) {
    private var inventoryOnly = false
    private lateinit var tabAllBtn: SkinButton
    private lateinit var tabInvBtn: SkinButton

    override val gridTopOffset: Int = 64

    override fun buildExtraHeaderWidgets() {
        val tabW = (winW - 24) / 2
        tabAllBtn = addWidget(
            SkinButton({ skin }, winX + 10, winY + 44, tabW, 16, Component.translatable("abadoned_greatwork.notes.picker.tab_all"), "button") {
                inventoryOnly = false
                updateTabButtons()
                reloadEntries()
                setModalFocus(searchField)
            }
        )
        tabInvBtn = addWidget(
            SkinButton({ skin }, winX + 14 + tabW, winY + 44, tabW, 16, Component.translatable("abadoned_greatwork.notes.picker.tab_inv"), "button") {
                inventoryOnly = true
                updateTabButtons()
                reloadEntries()
                setModalFocus(searchField)
            }
        )
        updateTabButtons()
    }

    private fun updateTabButtons() {
        tabAllBtn.selected = !inventoryOnly
        tabInvBtn.selected = inventoryOnly
    }

    override fun loadAllEntries(): List<ItemChoice> {
        if (inventoryOnly) {
            val player = Minecraft.getInstance().player ?: return emptyList()
            val seen = LinkedHashSet<String>()
            val out = mutableListOf<ItemChoice>()
            for (i in 0 until player.inventory.containerSize) {
                val st = player.inventory.getItem(i)
                if (st.isEmpty) continue
                val id = BuiltInRegistries.ITEM.getKey(st.item).toString()
                if (seen.add(id)) {
                    out.add(ItemChoice(id, st.hoverName.string, ItemStack(st.item)))
                }
            }
            return out
        }
        return BuiltInRegistries.ITEM.keySet().sortedBy { it.toString() }.mapNotNull { rl ->
            val item = BuiltInRegistries.ITEM.get(rl)
            val st = ItemStack(item)
            if (st.isEmpty) null else ItemChoice(rl.toString(), st.hoverName.string, st)
        }
    }

    override fun matchesQuery(item: ItemChoice, q: String): Boolean =
        item.name.lowercase().contains(q) || item.id.lowercase().contains(q)

    override fun renderCell(graphics: GuiGraphics, item: ItemChoice, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val spriteState = if (selected) "selected" else if (hovered) "hover" else "normal"
        SkinRenderer.drawSprite(graphics, skin, "card", x, y, w, h, spriteState)
        graphics.renderItem(item.stack, x + (w - 16) / 2, y + (h - 16) / 2)
    }

    override fun tooltipFor(item: ItemChoice): List<Component> = listOf(
        Component.literal(item.name),
        Component.literal(item.id).withStyle(ChatFormatting.DARK_GRAY),
    )
}

data class RecipeChoice(val id: String, val name: String, val output: ItemStack)

class RecipePickerModal(
    private val recipeType: RecipeType<*>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) : GridSearchModal<RecipeChoice>(
    title = Component.translatable("abadoned_greatwork.notes.picker.recipe"),
    cols = 9,
    cellW = 28,
    cellH = 28,
    onDismiss = onDismiss,
    onPick = { onPick(it.id) },
) {
    override fun loadAllEntries(): List<RecipeChoice> {
        val level = Minecraft.getInstance().level ?: return emptyList()
        val regAccess = level.registryAccess()
        return level.recipeManager.recipes
            .filter { it.value.type == recipeType }
            .map { holder ->
                val out = holder.value.getResultItem(regAccess)
                RecipeChoice(holder.id.toString(), out.hoverName.string, out)
            }
            .sortedBy { it.name }
    }

    override fun matchesQuery(item: RecipeChoice, q: String): Boolean =
        item.name.lowercase().contains(q) || item.id.lowercase().contains(q)

    override fun renderCell(graphics: GuiGraphics, item: RecipeChoice, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val spriteState = if (selected) "selected" else if (hovered) "hover" else "normal"
        SkinRenderer.drawSprite(graphics, skin, "card", x, y, w, h, spriteState)
        if (!item.output.isEmpty) {
            graphics.renderItem(item.output, x + (w - 16) / 2, y + (h - 16) / 2)
        }
    }

    override fun tooltipFor(item: RecipeChoice): List<Component> = listOf(
        Component.literal(item.name),
        Component.literal(item.id).withStyle(ChatFormatting.DARK_GRAY),
    )
}

data class ActionChoice(val id: String, val label: String, val pattern: HexPattern)

class ActionPickerModal(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) : GridSearchModal<ActionChoice>(
    title = Component.translatable("abadoned_greatwork.notes.picker.action"),
    cols = 2,
    cellW = 128,
    cellH = 34,
    onDismiss = onDismiss,
    onPick = { onPick(it.id) },
) {
    override fun loadAllEntries(): List<ActionChoice> {
        val excluded = ClientNoteLibrary.excludedActions
        return HexActions.REGISTRY.entrySet()
            .filter { (key, _) -> key.location().toString() !in excluded }
            .map { (key, entry) ->
                val id = key.location().toString()
                val langKey = "hexcasting.action.$id"
                val label = if (I18n.exists(langKey)) I18n.get(langKey) else id
                ActionChoice(id, label, entry.prototype())
            }
            .sortedBy { it.label }
    }

    override fun matchesQuery(item: ActionChoice, q: String): Boolean =
        item.label.lowercase().contains(q) || item.id.lowercase().contains(q)

    override fun renderCell(graphics: GuiGraphics, item: ActionChoice, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val spriteState = if (selected) "selected" else if (hovered) "hover" else "normal"
        SkinRenderer.drawSprite(graphics, skin, "card", x, y, w, h, spriteState)
        NoteVisualHelpers.renderHexPattern(graphics, item.pattern, x + 3, y + 3, 28, h - 6, stroke = 2.5, hexSize = 10.0, key = "abw_pick")
        val font = Minecraft.getInstance().font
        val trimmed = font.plainSubstrByWidth(item.label, w - 38)
        graphics.drawString(font, trimmed, x + 34, y + (h - 8) / 2, skin.color("text", 0xFF_F2EBF9.toInt()), false)
    }

    override fun tooltipFor(item: ActionChoice): List<Component> = listOf(
        Component.literal(item.label),
        Component.literal(item.id).withStyle(ChatFormatting.DARK_GRAY),
    )
}

data class EntityChoice(val id: String, val name: String)

class EntityPickerModal(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) : GridSearchModal<EntityChoice>(
    title = Component.translatable("abadoned_greatwork.notes.picker.entity"),
    cols = 1,
    cellW = 166,
    cellH = 17,
    onDismiss = onDismiss,
    onPick = { onPick(it.id) },
) {
    override val gridRightReserve: Int = 92

    override fun loadAllEntries(): List<EntityChoice> =
        BuiltInRegistries.ENTITY_TYPE.keySet().sortedBy { it.toString() }.map { rl ->
            EntityChoice(rl.toString(), BuiltInRegistries.ENTITY_TYPE.get(rl).description.string)
        }

    override fun matchesQuery(item: EntityChoice, q: String): Boolean =
        item.name.lowercase().contains(q) || item.id.lowercase().contains(q)

    override fun renderCell(graphics: GuiGraphics, item: EntityChoice, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val sprite = if (selected || hovered) "list_item_selected" else "list_item_normal"
        SkinRenderer.drawSprite(graphics, skin, sprite, x, y, w, h)
        val font = Minecraft.getInstance().font
        graphics.drawString(font, font.plainSubstrByWidth(item.name, w - 10), x + 5, y + 4, skin.color("text", 0xFF_F2EBF9.toInt()), false)
    }

    override fun renderSidePreview(graphics: GuiGraphics, mouseX: Int, mouseY: Int, activeItem: EntityChoice?) {
        val px = gridX + gridW + 6
        val py = gridY
        val pw = gridRightReserve - 6
        val ph = gridH
        SkinRenderer.drawSprite(graphics, skin, "sub_panel", px, py, pw, ph)
        if (activeItem != null) {
            val rot = (System.currentTimeMillis() / 25L % 360L).toFloat()
            val ok = NoteVisualHelpers.renderEntityPreview(graphics, activeItem.id, px + pw / 2f, py + ph / 2f + 4f, 64f, 1.0f, rot)
            val font = Minecraft.getInstance().font
            if (!ok) {
                SkinRenderer.drawCenteredNoShadow(graphics, font, "?", px + pw / 2, py + ph / 2 - 4, skin.color("text_dim", 0xFF_A293B8.toInt()))
            }
            graphics.drawString(font, font.plainSubstrByWidth(activeItem.id, pw - 8), px + 4, py + ph - 12, skin.color("text_dim", 0xFF_A293B8.toInt()), false)
        }
    }

    override fun tooltipFor(item: EntityChoice): List<Component> = listOf(
        Component.literal(item.name),
        Component.literal(item.id).withStyle(ChatFormatting.DARK_GRAY),
    )
}

data class ImageChoice(val rl: ResourceLocation, val id: String)

class ImagePickerModal(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) : GridSearchModal<ImageChoice>(
    title = Component.translatable("abadoned_greatwork.notes.picker.image"),
    cols = 8,
    cellW = 32,
    cellH = 32,
    onDismiss = onDismiss,
    onPick = { onPick(it.id) },
) {
    private var useTypedBtn: SkinButton? = null

    override fun extraSearchRightReserve(): Int = 62

    override fun buildExtraHeaderWidgets() {
        useTypedBtn = addWidget(
            SkinButton({ skin }, winX + winW - 68, winY + 25, 58, 16, Component.translatable("abadoned_greatwork.notes.btn.use_path"), "button_accent") {
                val rl = ResourceLocation.tryParse(query.trim())
                if (rl != null) {
                    dismiss()
                    onPick(ImageChoice(rl, rl.toString()))
                }
            }
        )
        updateTypedBtn()
    }

    private fun updateTypedBtn() {
        val rl = ResourceLocation.tryParse(query.trim())
        useTypedBtn?.active = rl != null && Minecraft.getInstance().resourceManager.getResource(rl).isPresent
    }

    override fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        updateTypedBtn()
        super.renderContent(graphics, mouseX, mouseY, partialTicks)
    }

    override fun loadAllEntries(): List<ImageChoice> {
        val rm = Minecraft.getInstance().resourceManager
        val found = rm.listResources("textures") { loc ->
            loc.path.endsWith(".png") &&
                (loc.path.contains("/gui/") || loc.path.contains("/item/") || loc.path.contains("/entries/") || loc.namespace == Abadoned_greatwork.MODID)
        }
        return found.keys
            .sortedWith(compareBy<ResourceLocation>({ if (it.namespace == Abadoned_greatwork.MODID || it.namespace == "hexcasting") 0 else 1 }, { it.toString() }))
            .take(400)
            .map { ImageChoice(it, it.toString()) }
    }

    override fun matchesQuery(item: ImageChoice, q: String): Boolean =
        item.id.lowercase().contains(q)

    override fun renderCell(graphics: GuiGraphics, item: ImageChoice, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val spriteState = if (selected) "selected" else if (hovered) "hover" else "normal"
        SkinRenderer.drawSprite(graphics, skin, "card", x, y, w, h, spriteState)
        graphics.blit(item.rl, x + 3, y + 3, w - 6, h - 6, 0f, 0f, 16, 16, 16, 16)
    }

    override fun tooltipFor(item: ImageChoice): List<Component> = listOf(Component.literal(item.id))
}

data class JsonFileChoice(val fileName: String, val title: String, val pageCount: Int)

class JsonImportModal(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) : GridSearchModal<JsonFileChoice>(
    title = Component.translatable("abadoned_greatwork.notes.picker.json_import"),
    cols = 1,
    cellW = 260,
    cellH = 24,
    onDismiss = onDismiss,
    onPick = { onPick(it.fileName) },
) {
    override fun loadAllEntries(): List<JsonFileChoice> =
        NoteFileIO.listJsonFiles().map { name ->
            val path = NoteFileIO.notesDir.resolve(name)
            val parsed = runCatching {
                NoteEntry.parseJsonString(Files.readString(path, StandardCharsets.UTF_8))
            }.getOrNull()
            JsonFileChoice(
                fileName = name,
                title = parsed?.title?.ifBlank { name } ?: name,
                pageCount = parsed?.pages?.size ?: 0,
            )
        }

    override fun matchesQuery(item: JsonFileChoice, q: String): Boolean =
        item.title.lowercase().contains(q) || item.fileName.lowercase().contains(q)

    override fun renderCell(graphics: GuiGraphics, item: JsonFileChoice, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val sprite = if (selected || hovered) "list_item_selected" else "list_item_normal"
        SkinRenderer.drawSprite(graphics, skin, sprite, x, y, w, h)
        val font = Minecraft.getInstance().font
        SkinRenderer.drawSprite(graphics, skin, "icon.import", x + 5, y + 5, 12, 12)
        val meta = if (item.pageCount > 0) "${item.title} (${item.pageCount}p)" else item.title
        graphics.drawString(font, font.plainSubstrByWidth(meta, w - 26), x + 22, y + 3, skin.color("text", 0xFF_F2EBF9.toInt()), false)
        graphics.drawString(font, font.plainSubstrByWidth(item.fileName, w - 26), x + 22, y + 12, skin.color("text_dim", 0xFF_A293B8.toInt()), false)
    }

    override fun tooltipFor(item: JsonFileChoice): List<Component> = listOf(
        Component.literal(item.title),
        Component.literal(item.fileName).withStyle(ChatFormatting.DARK_GRAY),
    )
}
