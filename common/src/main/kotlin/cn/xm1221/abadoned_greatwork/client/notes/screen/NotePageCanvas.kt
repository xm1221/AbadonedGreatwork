package cn.xm1221.abadoned_greatwork.client.notes.screen

import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.api.anglesSignature
import cn.xm1221.abadoned_greatwork.api.iotaColor
import cn.xm1221.abadoned_greatwork.api.iotaDisplay
import cn.xm1221.abadoned_greatwork.api.patternFromAngles
import cn.xm1221.abadoned_greatwork.api.startDir
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinModal
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.ActionPickerModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.EntityPickerModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.ImagePickerModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.ItemPickerModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.PageTypePickerModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.PatternDrawModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.RecipePickerModal
import cn.xm1221.abadoned_greatwork.client.notes.screen.modal.SnbtPasteModal
import cn.xm1221.abadoned_greatwork.notes.ManualPatternDef
import cn.xm1221.abadoned_greatwork.notes.NotePage
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import java.util.Locale

/**
 * 编辑器左页羊皮纸画布：根据当前 `NotePageType` 展示所见即所得的拼装/绘制交互区，
 * 所有选择器均通过可读弹窗完成，避免直接暴露底层 ID、配方路径或 qweasd 签名。
 */
class NotePageCanvas(
    private val skinSupplier: () -> GuiSkin,
    var x: Int,
    var y: Int,
    var w: Int,
    var h: Int,
    private val pageSupplier: () -> NotePage,
    private val pageConsumer: (NotePage) -> Unit,
    private val openModal: (SkinModal) -> Unit,
    private val textLineCountSupplier: () -> Int,
    private val capacitySupplier: () -> Int,
) {
    private val widgets = mutableListOf<AbstractWidget>()
    private var focusedWidget: GuiEventListener? = null
    private var suppressCallbacks = false

    private var activePatternIndex = 0
    private var activeImageIndex = 0
    private var iotaScroll = 0
    private var lastRebuiltType: NotePageType? = null

    private var previewHoverRect: RectBox? = null
    private var slotTooltips = mutableListOf<SlotTooltip>()

    private val innerX: Int get() = x + 10
    private val innerY: Int get() = y + 8
    private val innerW: Int get() = w - 20
    private val innerH: Int get() = h - 16
    private val bodyY: Int get() = innerY + 38

    private data class RectBox(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(mx: Double, my: Double): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
    }

    private data class SlotTooltip(val box: RectBox, val lines: List<Component>)

    fun rebuild(resetSubIndices: Boolean = false) {
        val font = Minecraft.getInstance().font
        val page = pageSupplier()
        if (resetSubIndices || lastRebuiltType != page.type) {
            activePatternIndex = 0
            activeImageIndex = 0
            iotaScroll = 0
            lastRebuiltType = page.type
        }
        activePatternIndex = activePatternIndex.coerceIn(0, (page.patterns.size - 1).coerceAtLeast(0))
        activeImageIndex = activeImageIndex.coerceIn(0, (page.images.size - 1).coerceAtLeast(0))
        iotaScroll = iotaScroll.coerceIn(0, (page.iotas.size - IOTA_VISIBLE_ROWS).coerceAtLeast(0))

        widgets.clear()
        focusedWidget = null
        previewHoverRect = null
        suppressCallbacks = true

        try {
            val iconSuffix = NoteVisualHelpers.pageIconSuffix(page.type)
            widgets += SkinButton(
                skinSupplier,
                innerX,
                innerY,
                innerW,
                16,
                Component.translatable("abadoned_greatwork.notes.canvas.type_btn", I18n.get(page.type.langKey)),
                "paper_button",
                "icon.page.$iconSuffix",
            ) {
                openModal(
                    PageTypePickerModal(pageSupplier().type, onDismiss = {}) { pickedType ->
                        if (pickedType != pageSupplier().type) {
                            updatePage(pageSupplier().copy(type = pickedType), rebuild = true)
                        }
                    }
                )
            }

            if (page.type != NotePageType.EMPTY) {
                val isEntity = page.type == NotePageType.ENTITY
                val headingField = SkinTextField(
                    skinSupplier,
                    font,
                    innerX,
                    innerY + 19,
                    innerW,
                    15,
                    Component.translatable("abadoned_greatwork.notes.canvas.heading"),
                    "paper_field",
                )
                headingField.setMaxLength(NoteValidator.MAX_TITLE_LENGTH)
                headingField.setHint(
                    Component.translatable(
                        if (isEntity) "abadoned_greatwork.notes.canvas.entity_name_hint"
                        else "abadoned_greatwork.notes.canvas.heading_hint"
                    )
                )
                headingField.value = if (isEntity) page.field("name") else page.title
                headingField.setResponder { value ->
                    if (suppressCallbacks) return@setResponder
                    val cur = pageSupplier()
                    val next = if (isEntity) cur.withField("name", value) else cur.copy(title = value)
                    pageConsumer(next)
                }
                widgets += headingField
            }

            when (page.type) {
                NotePageType.TEXT -> buildTextControls()
                NotePageType.SPOTLIGHT -> buildSpotlightControls(page)
                NotePageType.CRAFTING -> buildRecipeControls(page, RecipeType.CRAFTING)
                NotePageType.SMELTING -> buildRecipeControls(page, RecipeType.SMELTING)
                NotePageType.HEX_PATTERN -> buildHexPatternControls(page)
                NotePageType.HEX_MANUAL_PATTERN -> buildManualPatternControls(page)
                NotePageType.IMAGE -> buildImageControls(page)
                NotePageType.ENTITY -> buildEntityControls(page)
                NotePageType.LINK -> buildLinkControls(page)
                NotePageType.EMPTY -> buildEmptyControls(page)
                NotePageType.IOTA_DISPLAY -> buildIotaControls(page)
            }
        } finally {
            suppressCallbacks = false
        }
    }

    private fun updatePage(next: NotePage, rebuild: Boolean = false) {
        pageConsumer(next)
        if (rebuild) rebuild(resetSubIndices = false)
    }

    private fun buildTextControls() {
        // TEXT 类型左侧仅做装饰与字数/行数统计预览
    }

    private fun buildSpotlightControls(page: NotePage) {
        widgets += SkinButton(
            skinSupplier,
            innerX + 42,
            bodyY + 20,
            innerW - 42,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.choose_item"),
            "paper_button",
            "icon.search",
        ) {
            openModal(
                ItemPickerModal(onDismiss = {}) { pickedId ->
                    updatePage(pageSupplier().withField("item", pickedId), rebuild = false)
                }
            )
        }

        val linked = page.boolField("link_recipe", false)
        val linkBtn = SkinButton(
            skinSupplier,
            innerX,
            bodyY + 46,
            innerW,
            16,
            Component.translatable(
                "abadoned_greatwork.notes.canvas.link_recipe",
                Component.translatable(if (linked) "abadoned_greatwork.notes.state.on" else "abadoned_greatwork.notes.state.off"),
            ),
            "paper_button",
        ) {
            val cur = pageSupplier()
            val next = !cur.boolField("link_recipe", false)
            updatePage(cur.withField("link_recipe", next.toString()), rebuild = true)
        }
        linkBtn.selected = linked
        widgets += linkBtn
    }

    private fun buildRecipeControls(page: NotePage, recipeType: RecipeType<*>) {
        val rightX = innerX + 108
        val rightW = (innerW - 108).coerceAtLeast(48)
        widgets += SkinButton(
            skinSupplier,
            rightX,
            bodyY + 6,
            rightW,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.pick_recipe"),
            "paper_button",
        ) {
            openModal(
                RecipePickerModal(recipeType, onDismiss = {}) { pickedId ->
                    updatePage(pageSupplier().withField("recipe", pickedId), rebuild = true)
                }
            )
        }
        val clear1 = SkinButton(
            skinSupplier,
            rightX,
            bodyY + 26,
            rightW,
            15,
            Component.translatable("abadoned_greatwork.notes.canvas.clear"),
            "paper_button",
        ) {
            updatePage(pageSupplier().withField("recipe", ""), rebuild = true)
        }
        clear1.active = page.field("recipe").isNotEmpty()
        widgets += clear1

        val recipe2Id = page.field("recipe2")
        val row2Y = bodyY + 66
        if (recipe2Id.isEmpty()) {
            widgets += SkinButton(
                skinSupplier,
                innerX,
                row2Y,
                innerW,
                16,
                Component.translatable("abadoned_greatwork.notes.canvas.add_recipe2"),
                "paper_button",
                "icon.add",
            ) {
                openModal(
                    RecipePickerModal(recipeType, onDismiss = {}) { pickedId ->
                        updatePage(pageSupplier().withField("recipe2", pickedId), rebuild = true)
                    }
                )
            }
        } else {
            val r2Label = resolveRecipeOutputName(recipe2Id, recipeType)
            widgets += SkinButton(
                skinSupplier,
                innerX,
                row2Y,
                innerW - 20,
                16,
                Component.translatable("abadoned_greatwork.notes.canvas.recipe2_item", r2Label),
                "paper_button",
            ) {
                openModal(
                    RecipePickerModal(recipeType, onDismiss = {}) { pickedId ->
                        updatePage(pageSupplier().withField("recipe2", pickedId), rebuild = true)
                    }
                )
            }
            widgets += SkinButton(
                skinSupplier,
                innerX + innerW - 18,
                row2Y,
                18,
                16,
                Component.empty(),
                "paper_button",
                "icon.delete",
            ) {
                updatePage(pageSupplier().withField("recipe2", ""), rebuild = true)
            }
        }
    }

    private fun buildHexPatternControls(page: NotePage) {
        val font = Minecraft.getInstance().font
        val rx = innerX + 76
        val rw = innerW - 76

        previewHoverRect = RectBox(innerX, bodyY + 2, 72, 72)

        widgets += SkinButton(
            skinSupplier,
            rx,
            bodyY + 2,
            rw,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.pick_action"),
            "paper_button",
            "icon.search",
        ) {
            openModal(
                ActionPickerModal(onDismiss = {}) { pickedAction ->
                    updatePage(pageSupplier().withField("op_id", pickedAction), rebuild = false)
                }
            )
        }

        val inField = SkinTextField(skinSupplier, font, rx, bodyY + 42, rw, 14, Component.literal("Input"), "paper_field")
        inField.setMaxLength(128)
        inField.setHint(Component.translatable("abadoned_greatwork.notes.canvas.input_hint"))
        inField.value = page.field("input")
        inField.setResponder { v ->
            if (!suppressCallbacks) pageConsumer(pageSupplier().withField("input", v))
        }
        widgets += inField

        val outField = SkinTextField(skinSupplier, font, rx, bodyY + 60, rw, 14, Component.literal("Output"), "paper_field")
        outField.setMaxLength(128)
        outField.setHint(Component.translatable("abadoned_greatwork.notes.canvas.output_hint"))
        outField.value = page.field("output")
        outField.setResponder { v ->
            if (!suppressCallbacks) pageConsumer(pageSupplier().withField("output", v))
        }
        widgets += outField
    }

    private fun buildManualPatternControls(page: NotePage) {
        val font = Minecraft.getInstance().font
        val rx = innerX + 76
        val rw = innerW - 76

        previewHoverRect = RectBox(innerX, bodyY + 2, 72, 72)

        widgets += SkinButton(
            skinSupplier,
            rx,
            bodyY + 2,
            rw,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.draw_pattern"),
            "paper_button",
            "icon.draw",
        ) {
            openDrawPatternForCurrent(addNew = pageSupplier().patterns.isEmpty())
        }

        val count = page.patterns.size
        val stepY = bodyY + 21
        val prevBtn = SkinButton(skinSupplier, rx, stepY, 15, 15, Component.empty(), "paper_button", "icon.left") {
            if (activePatternIndex > 0) {
                activePatternIndex--
                rebuild()
            }
        }
        prevBtn.active = activePatternIndex > 0
        widgets += prevBtn

        val nextBtn = SkinButton(skinSupplier, rx + 39, stepY, 15, 15, Component.empty(), "paper_button", "icon.right") {
            if (activePatternIndex < pageSupplier().patterns.lastIndex) {
                activePatternIndex++
                rebuild()
            }
        }
        nextBtn.active = activePatternIndex < count - 1
        widgets += nextBtn

        val addBtn = SkinButton(skinSupplier, rx + 56, stepY, 15, 15, Component.empty(), "paper_button", "icon.add") {
            openDrawPatternForCurrent(addNew = true)
        }
        addBtn.active = count < NoteValidator.MAX_PATTERNS_PER_PAGE
        widgets += addBtn

        val delBtn = SkinButton(skinSupplier, rx + 73, stepY, 15, 15, Component.empty(), "paper_button", "icon.delete") {
            val cur = pageSupplier()
            if (cur.patterns.isNotEmpty() && activePatternIndex in cur.patterns.indices) {
                val mutable = cur.patterns.toMutableList()
                mutable.removeAt(activePatternIndex)
                activePatternIndex = activePatternIndex.coerceIn(0, (mutable.size - 1).coerceAtLeast(0))
                updatePage(cur.copy(patterns = mutable), rebuild = true)
            }
        }
        delBtn.active = count > 0
        widgets += delBtn

        val inField = SkinTextField(skinSupplier, font, rx, bodyY + 40, rw, 14, Component.literal("Input"), "paper_field")
        inField.setMaxLength(128)
        inField.setHint(Component.translatable("abadoned_greatwork.notes.canvas.input_hint"))
        inField.value = page.field("input")
        inField.setResponder { v ->
            if (!suppressCallbacks) pageConsumer(pageSupplier().withField("input", v))
        }
        widgets += inField

        val outField = SkinTextField(skinSupplier, font, rx, bodyY + 57, rw, 14, Component.literal("Output"), "paper_field")
        outField.setMaxLength(128)
        outField.setHint(Component.translatable("abadoned_greatwork.notes.canvas.output_hint"))
        outField.value = page.field("output")
        outField.setResponder { v ->
            if (!suppressCallbacks) pageConsumer(pageSupplier().withField("output", v))
        }
        widgets += outField

        val strokeOn = page.boolField("stroke_order", true)
        val strokeBtn = SkinButton(
            skinSupplier,
            innerX,
            bodyY + 78,
            innerW,
            15,
            Component.translatable(
                "abadoned_greatwork.notes.canvas.stroke_order",
                Component.translatable(if (strokeOn) "abadoned_greatwork.notes.state.on" else "abadoned_greatwork.notes.state.off"),
            ),
            "paper_button",
        ) {
            val cur = pageSupplier()
            val next = !cur.boolField("stroke_order", true)
            updatePage(cur.withField("stroke_order", next.toString()), rebuild = true)
        }
        strokeBtn.selected = strokeOn
        widgets += strokeBtn
    }

    private fun openDrawPatternForCurrent(addNew: Boolean) {
        val cur = pageSupplier()
        val initialPat = if (!addNew && activePatternIndex in cur.patterns.indices) {
            parsePattern(cur.patterns[activePatternIndex])
        } else {
            null
        }
        openModal(
            PatternDrawModal(initialPat, onDismiss = {}) { drawn ->
                val latest = pageSupplier()
                val def = ManualPatternDef(drawn.startDir.name, drawn.anglesSignature())
                val mutable = latest.patterns.toMutableList()
                if (addNew || mutable.isEmpty() || activePatternIndex !in mutable.indices) {
                    if (mutable.size < NoteValidator.MAX_PATTERNS_PER_PAGE) {
                        mutable.add(def)
                        activePatternIndex = mutable.lastIndex
                    }
                } else {
                    mutable[activePatternIndex] = def
                }
                updatePage(latest.copy(patterns = mutable), rebuild = true)
            }
        )
    }

    private fun buildImageControls(page: NotePage) {
        val rx = innerX + 76
        val rw = innerW - 76

        widgets += SkinButton(
            skinSupplier,
            rx,
            bodyY + 2,
            rw,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.choose_image"),
            "paper_button",
            "icon.search",
        ) {
            openModal(
                ImagePickerModal(onDismiss = {}) { picked ->
                    val latest = pageSupplier()
                    val mutable = latest.images.toMutableList()
                    if (mutable.isEmpty() || activeImageIndex !in mutable.indices) {
                        mutable.add(picked)
                        activeImageIndex = mutable.lastIndex
                    } else {
                        mutable[activeImageIndex] = picked
                    }
                    updatePage(latest.copy(images = mutable), rebuild = true)
                }
            )
        }

        val count = page.images.size
        val stepY = bodyY + 21
        val prevBtn = SkinButton(skinSupplier, rx, stepY, 15, 15, Component.empty(), "paper_button", "icon.left") {
            if (activeImageIndex > 0) {
                activeImageIndex--
                rebuild()
            }
        }
        prevBtn.active = activeImageIndex > 0
        widgets += prevBtn

        val nextBtn = SkinButton(skinSupplier, rx + 39, stepY, 15, 15, Component.empty(), "paper_button", "icon.right") {
            if (activeImageIndex < pageSupplier().images.lastIndex) {
                activeImageIndex++
                rebuild()
            }
        }
        nextBtn.active = activeImageIndex < count - 1
        widgets += nextBtn

        val addBtn = SkinButton(skinSupplier, rx + 56, stepY, 15, 15, Component.empty(), "paper_button", "icon.add") {
            openModal(
                ImagePickerModal(onDismiss = {}) { picked ->
                    val latest = pageSupplier()
                    if (latest.images.size < NoteValidator.MAX_IMAGES_PER_PAGE) {
                        val mutable = latest.images.toMutableList()
                        mutable.add(picked)
                        activeImageIndex = mutable.lastIndex
                        updatePage(latest.copy(images = mutable), rebuild = true)
                    }
                }
            )
        }
        addBtn.active = count < NoteValidator.MAX_IMAGES_PER_PAGE
        widgets += addBtn

        val delBtn = SkinButton(skinSupplier, rx + 73, stepY, 15, 15, Component.empty(), "paper_button", "icon.delete") {
            val latest = pageSupplier()
            if (activeImageIndex in latest.images.indices) {
                val mutable = latest.images.toMutableList()
                mutable.removeAt(activeImageIndex)
                activeImageIndex = activeImageIndex.coerceIn(0, (mutable.size - 1).coerceAtLeast(0))
                updatePage(latest.copy(images = mutable), rebuild = true)
            }
        }
        delBtn.active = count > 0
        widgets += delBtn

        val borderOn = page.boolField("border", true)
        val borderBtn = SkinButton(
            skinSupplier,
            rx,
            bodyY + 40,
            rw,
            16,
            Component.translatable(
                "abadoned_greatwork.notes.canvas.border",
                Component.translatable(if (borderOn) "abadoned_greatwork.notes.state.on" else "abadoned_greatwork.notes.state.off"),
            ),
            "paper_button",
        ) {
            val cur = pageSupplier()
            val next = !cur.boolField("border", true)
            updatePage(cur.withField("border", next.toString()), rebuild = true)
        }
        borderBtn.selected = borderOn
        widgets += borderBtn
    }

    private fun buildEntityControls(page: NotePage) {
        val rx = innerX + 76
        val rw = innerW - 76

        widgets += SkinButton(
            skinSupplier,
            rx,
            bodyY + 2,
            rw,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.pick_entity"),
            "paper_button",
            "icon.search",
        ) {
            openModal(
                EntityPickerModal(onDismiss = {}) { pickedId ->
                    updatePage(pageSupplier().withField("entity", pickedId), rebuild = false)
                }
            )
        }

        widgets += SkinButton(skinSupplier, rx + rw - 34, bodyY + 34, 16, 14, Component.literal("-"), "paper_button") {
            val cur = pageSupplier()
            val v = ((cur.field("scale").toFloatOrNull() ?: 1.0f) - 0.25f).coerceIn(0.25f, 3.0f)
            updatePage(cur.withField("scale", String.format(Locale.ROOT, "%.2f", v)), rebuild = false)
        }
        widgets += SkinButton(skinSupplier, rx + rw - 16, bodyY + 34, 16, 14, Component.literal("+"), "paper_button") {
            val cur = pageSupplier()
            val v = ((cur.field("scale").toFloatOrNull() ?: 1.0f) + 0.25f).coerceIn(0.25f, 3.0f)
            updatePage(cur.withField("scale", String.format(Locale.ROOT, "%.2f", v)), rebuild = false)
        }

        widgets += SkinButton(skinSupplier, rx + rw - 34, bodyY + 54, 16, 14, Component.literal("-"), "paper_button") {
            val cur = pageSupplier()
            val v = ((cur.field("offset").toFloatOrNull() ?: 0.0f) - 0.2f).coerceIn(-2.0f, 2.0f)
            updatePage(cur.withField("offset", String.format(Locale.ROOT, "%.1f", v)), rebuild = false)
        }
        widgets += SkinButton(skinSupplier, rx + rw - 16, bodyY + 54, 16, 14, Component.literal("+"), "paper_button") {
            val cur = pageSupplier()
            val v = ((cur.field("offset").toFloatOrNull() ?: 0.0f) + 0.2f).coerceIn(-2.0f, 2.0f)
            updatePage(cur.withField("offset", String.format(Locale.ROOT, "%.1f", v)), rebuild = false)
        }

        val rotateOn = page.boolField("rotate", true)
        val rotateBtn = SkinButton(
            skinSupplier,
            rx,
            bodyY + 74,
            rw,
            15,
            Component.translatable(
                "abadoned_greatwork.notes.canvas.rotate",
                Component.translatable(if (rotateOn) "abadoned_greatwork.notes.state.on" else "abadoned_greatwork.notes.state.off"),
            ),
            "paper_button",
        ) {
            val cur = pageSupplier()
            val next = !cur.boolField("rotate", true)
            updatePage(cur.withField("rotate", next.toString()), rebuild = true)
        }
        rotateBtn.selected = rotateOn
        widgets += rotateBtn
    }

    private fun buildLinkControls(page: NotePage) {
        val font = Minecraft.getInstance().font
        val urlField = SkinTextField(skinSupplier, font, innerX, bodyY + 12, innerW, 15, Component.literal("URL"), "paper_field")
        urlField.setMaxLength(512)
        urlField.setHint(Component.literal("https://..."))
        urlField.value = page.field("url")
        urlField.setResponder { v ->
            if (!suppressCallbacks) pageConsumer(pageSupplier().withField("url", v))
        }
        widgets += urlField

        val labelField = SkinTextField(skinSupplier, font, innerX, bodyY + 42, innerW, 15, Component.literal("Label"), "paper_field")
        labelField.setMaxLength(64)
        labelField.setHint(Component.translatable("abadoned_greatwork.notes.canvas.link_label_hint"))
        labelField.value = page.field("link_text")
        labelField.setResponder { v ->
            if (!suppressCallbacks) pageConsumer(pageSupplier().withField("link_text", v))
        }
        widgets += labelField
    }

    private fun buildEmptyControls(page: NotePage) {
        val fillerOn = page.boolField("draw_filler", true)
        val fillerBtn = SkinButton(
            skinSupplier,
            innerX + 10,
            innerY + 86,
            innerW - 20,
            16,
            Component.translatable(
                "abadoned_greatwork.notes.canvas.draw_filler",
                Component.translatable(if (fillerOn) "abadoned_greatwork.notes.state.on" else "abadoned_greatwork.notes.state.off"),
            ),
            "paper_button",
        ) {
            val cur = pageSupplier()
            val next = !cur.boolField("draw_filler", true)
            updatePage(cur.withField("draw_filler", next.toString()), rebuild = true)
        }
        fillerBtn.selected = fillerOn
        widgets += fillerBtn
    }

    private fun buildIotaControls(page: NotePage) {
        val addBtn = SkinButton(
            skinSupplier,
            innerX,
            bodyY + 78,
            innerW - 36,
            16,
            Component.translatable("abadoned_greatwork.notes.canvas.paste_iota"),
            "paper_button",
            "icon.add",
        ) {
            openModal(
                SnbtPasteModal(onDismiss = {}) { tag ->
                    val latest = pageSupplier()
                    if (latest.iotas.size < NoteValidator.MAX_IOTAS_PER_PAGE) {
                        val mutable = latest.iotas.toMutableList()
                        mutable.add(tag.copy())
                        iotaScroll = (mutable.size - IOTA_VISIBLE_ROWS).coerceAtLeast(0)
                        updatePage(latest.copy(iotas = mutable), rebuild = true)
                    }
                }
            )
        }
        addBtn.active = page.iotas.size < NoteValidator.MAX_IOTAS_PER_PAGE
        widgets += addBtn
    }

    fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val s = skinSupplier()
        val font = Minecraft.getInstance().font
        val page = pageSupplier()
        slotTooltips.clear()

        when (page.type) {
            NotePageType.TEXT -> renderTextPreview(graphics, s)
            NotePageType.SPOTLIGHT -> renderSpotlightPreview(graphics, s, page)
            NotePageType.CRAFTING -> renderCraftingPreview(graphics, s, page)
            NotePageType.SMELTING -> renderSmeltingPreview(graphics, s, page)
            NotePageType.HEX_PATTERN -> renderHexPatternPreview(graphics, s, page)
            NotePageType.HEX_MANUAL_PATTERN -> renderManualPatternPreview(graphics, s, page)
            NotePageType.IMAGE -> renderImagePreview(graphics, s, page)
            NotePageType.ENTITY -> renderEntityStage(graphics, s, page)
            NotePageType.LINK -> renderLinkPreview(graphics, s, page)
            NotePageType.EMPTY -> renderEmptyPreview(graphics, s)
            NotePageType.IOTA_DISPLAY -> renderIotaList(graphics, s, page, mouseX, mouseY)
        }

        for (w in widgets) {
            w.render(graphics, mouseX, mouseY, partialTicks)
        }

        for (tip in slotTooltips) {
            if (tip.box.contains(mouseX.toDouble(), mouseY.toDouble()) && tip.lines.isNotEmpty()) {
                graphics.renderComponentTooltip(font, tip.lines, mouseX, mouseY)
                break
            }
        }
    }

    private fun renderTextPreview(graphics: GuiGraphics, s: GuiSkin) {
        val font = Minecraft.getInstance().font
        val ink = s.color("paper_text", 0xFF_2B1F16.toInt())
        val dim = s.color("paper_text_dim", 0xFF_6B5844.toInt())

        SkinRenderer.drawSprite(graphics, s, "paper_sub_panel", innerX, bodyY + 4, innerW, 84)
        SkinRenderer.drawSprite(graphics, s, "icon.page.text", innerX + (innerW - 12) / 2, bodyY + 14, 12, 12)
        SkinRenderer.drawDashedHLine(graphics, innerX + 16, bodyY + 30, innerW - 32, dim and 0x55FFFFFF)

        val tipLines = font.split(Component.translatable("abadoned_greatwork.notes.canvas.text_tip"), innerW - 18)
        var ty = bodyY + 38
        for (line in tipLines.take(3)) {
            graphics.drawString(font, line, innerX + 9, ty, dim, false)
            ty += 10
        }

        val count = textLineCountSupplier()
        val cap = capacitySupplier()
        val statusCol = if (count > cap) s.color("error", 0xFF_EC6270.toInt()) else ink
        val ratio = Component.translatable("abadoned_greatwork.notes.canvas.line_ratio", count, cap)
        SkinRenderer.drawCenteredNoShadow(graphics, font, ratio, innerX + innerW / 2, bodyY + 73, statusCol)
    }

    private fun renderSpotlightPreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val font = Minecraft.getInstance().font
        val ink = s.color("paper_text", 0xFF_2B1F16.toInt())
        val slotX = innerX + 2
        val slotY = bodyY + 4
        SkinRenderer.drawSprite(graphics, s, "paper_slot", slotX, slotY, 34, 34)

        val stack = NoteVisualHelpers.resolveItemStack(page.field("item", "minecraft:paper"))
        graphics.renderItem(stack, slotX + 9, slotY + 9)
        slotTooltips += SlotTooltip(RectBox(slotX, slotY, 34, 34), listOf(stack.hoverName))

        val itemTitle = Language.getInstance().getVisualOrder(font.substrByWidth(stack.hoverName, innerW - 44))
        graphics.drawString(font, itemTitle, innerX + 42, bodyY + 7, ink, false)
    }

    private fun renderCraftingPreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val gx = innerX + 2
        val gy = bodyY + 2
        SkinRenderer.drawSprite(graphics, s, "crafting_grid", gx, gy, 104, 58)

        val level = Minecraft.getInstance().level ?: return
        val recipeId = ResourceLocation.tryParse(page.field("recipe").trim())
        val recipe = recipeId?.let { level.recipeManager.byKey(it).orElse(null)?.value }
        if (recipe == null || recipe.type != RecipeType.CRAFTING) {
            val font = Minecraft.getInstance().font
            SkinRenderer.drawCenteredNoShadow(graphics, font, "?", gx + 27, gy + 25, s.color("paper_text_dim", 0xFF_6B5844.toInt()))
            return
        }

        val wrap = (recipe as? ShapedRecipe)?.width?.coerceIn(1, 3) ?: 3
        val ingredients = recipe.ingredients
        val cycleTick = (System.currentTimeMillis() / 1000L).toInt()
        for (i in ingredients.indices) {
            val col = i % wrap
            val row = i / wrap
            if (col >= 3 || row >= 3) continue
            val ingStack = pickCycleStack(ingredients[i], cycleTick)
            if (!ingStack.isEmpty) {
                val sx = gx + col * 18 + 1
                val sy = gy + 3 + row * 18
                graphics.renderItem(ingStack, sx, sy)
                slotTooltips += SlotTooltip(RectBox(sx, sy, 16, 16), listOf(ingStack.hoverName))
            }
        }
        val out = recipe.getResultItem(level.registryAccess())
        if (!out.isEmpty) {
            val ox = gx + 83
            val oy = gy + 21
            graphics.renderItem(out, ox, oy)
            graphics.renderItemDecorations(Minecraft.getInstance().font, out, ox, oy)
            slotTooltips += SlotTooltip(RectBox(ox, oy, 16, 16), listOf(out.hoverName))
        }
    }

    private fun renderSmeltingPreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val gx = innerX + 10
        val gy = bodyY + 2
        SkinRenderer.drawSprite(graphics, s, "smelting_frame", gx, gy, 86, 56)

        val level = Minecraft.getInstance().level ?: return
        val recipeId = ResourceLocation.tryParse(page.field("recipe").trim())
        val recipe = recipeId?.let { level.recipeManager.byKey(it).orElse(null)?.value }
        if (recipe == null || recipe.type != RecipeType.SMELTING) {
            val font = Minecraft.getInstance().font
            SkinRenderer.drawCenteredNoShadow(graphics, font, "?", gx + 11, gy + 7, s.color("paper_text_dim", 0xFF_6B5844.toInt()))
            return
        }

        val cycleTick = (System.currentTimeMillis() / 1000L).toInt()
        val inStack = recipe.ingredients.firstOrNull()?.let { pickCycleStack(it, cycleTick) } ?: ItemStack.EMPTY
        if (!inStack.isEmpty) {
            graphics.renderItem(inStack, gx + 3, gy + 3)
            slotTooltips += SlotTooltip(RectBox(gx + 3, gy + 3, 16, 16), listOf(inStack.hoverName))
        }
        val out = recipe.getResultItem(level.registryAccess())
        if (!out.isEmpty) {
            graphics.renderItem(out, gx + 59, gy + 20)
            graphics.renderItemDecorations(Minecraft.getInstance().font, out, gx + 59, gy + 20)
            slotTooltips += SlotTooltip(RectBox(gx + 59, gy + 20, 16, 16), listOf(out.hoverName))
        }
    }

    private fun renderHexPatternPreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val font = Minecraft.getInstance().font
        SkinRenderer.drawSprite(graphics, s, "sub_panel", innerX, bodyY + 2, 72, 72)

        val opId = page.field("op_id").trim()
        val rl = ResourceLocation.tryParse(opId)
        val entry = rl?.let { HexActions.REGISTRY.get(it) }
        if (entry != null) {
            NoteVisualHelpers.renderHexPattern(graphics, entry.prototype(), innerX + 6, bodyY + 8, 60, 60, stroke = 2.6, hexSize = 14.0, key = "abw_prev_act")
        } else {
            SkinRenderer.drawCenteredNoShadow(graphics, font, "?", innerX + 36, bodyY + 34, s.color("text_dim", 0xFF_A293B8.toInt()))
        }

        val rx = innerX + 76
        val rw = innerW - 76
        val langKey = "hexcasting.action.$opId"
        val labelComp = when {
            opId.isEmpty() -> Component.translatable("abadoned_greatwork.notes.canvas.no_action")
            I18n.exists(langKey) -> Component.translatable(langKey)
            else -> Component.literal(opId)
        }
        val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(labelComp, rw))
        graphics.drawString(font, trimmed, rx, bodyY + 22, s.color("paper_text", 0xFF_2B1F16.toInt()), false)

        val dim = s.color("paper_text_dim", 0xFF_6B5844.toInt())
        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.canvas.io_label"), rx, bodyY + 33, dim, false)
    }

    private fun renderManualPatternPreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val font = Minecraft.getInstance().font
        SkinRenderer.drawSprite(graphics, s, "sub_panel", innerX, bodyY + 2, 72, 72)

        val def = page.patterns.getOrNull(activePatternIndex)
        val pat = def?.let { parsePattern(it) }
        if (pat != null) {
            NoteVisualHelpers.renderHexPattern(graphics, pat, innerX + 6, bodyY + 8, 60, 60, stroke = 2.6, hexSize = 14.0, key = "abw_prev_man")
            slotTooltips += SlotTooltip(
                RectBox(innerX, bodyY + 2, 72, 72),
                listOf(Component.translatable("abadoned_greatwork.notes.canvas.click_to_redraw")),
            )
        } else {
            val hint = Component.translatable("abadoned_greatwork.notes.canvas.click_draw_hint")
            val split = font.split(hint, 60)
            var hy = bodyY + 28
            for (line in split.take(3)) {
                graphics.drawString(font, line, innerX + 8, hy, s.color("text_dim", 0xFF_A293B8.toInt()), false)
                hy += 9
            }
        }

        val rx = innerX + 76
        val count = page.patterns.size
        val badge = if (count == 0) "0/0" else "${activePatternIndex + 1}/$count"
        SkinRenderer.drawCenteredNoShadow(graphics, font, badge, rx + 27, bodyY + 24, s.color("paper_text", 0xFF_2B1F16.toInt()))
    }

    private fun renderImagePreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val font = Minecraft.getInstance().font
        SkinRenderer.drawSprite(graphics, s, "sub_panel", innerX, bodyY + 2, 72, 72)

        val rawImg = page.images.getOrNull(activeImageIndex).orEmpty()
        val rl = ResourceLocation.tryParse(rawImg.trim())
        if (rl != null && Minecraft.getInstance().resourceManager.getResource(rl).isPresent) {
            graphics.blit(rl, innerX + 4, bodyY + 6, 64, 64, 0f, 0f, 16, 16, 16, 16)
            slotTooltips += SlotTooltip(RectBox(innerX, bodyY + 2, 72, 72), listOf(Component.literal(rl.toString())))
        } else {
            SkinRenderer.drawCenteredNoShadow(graphics, font, "?", innerX + 36, bodyY + 34, s.color("text_dim", 0xFF_A293B8.toInt()))
        }

        val rx = innerX + 76
        val count = page.images.size
        val badge = if (count == 0) "0/0" else "${activeImageIndex + 1}/$count"
        SkinRenderer.drawCenteredNoShadow(graphics, font, badge, rx + 27, bodyY + 24, s.color("paper_text", 0xFF_2B1F16.toInt()))

        if (rawImg.isNotEmpty()) {
            val shortName = rawImg.substringAfterLast('/')
            val trimmed = font.plainSubstrByWidth(shortName, innerW - 76)
            graphics.drawString(font, trimmed, rx, bodyY + 62, s.color("paper_text_dim", 0xFF_6B5844.toInt()), false)
        }
    }

    private fun renderEntityStage(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val font = Minecraft.getInstance().font
        SkinRenderer.drawSprite(graphics, s, "sub_panel", innerX, bodyY + 2, 72, 78)

        val entityId = page.field("entity", "minecraft:allay").trim()
        val userScale = (page.field("scale").toFloatOrNull() ?: 1.0f).coerceIn(0.25f, 3.0f)
        val userOffset = (page.field("offset").toFloatOrNull() ?: 0.0f).coerceIn(-2.0f, 2.0f)
        val rotateOn = page.boolField("rotate", true)
        val defaultRot = page.field("default_rotation").toFloatOrNull() ?: -45.0f
        val rot = if (rotateOn) (System.currentTimeMillis() / 25L % 360L).toFloat() else defaultRot

        val ok = NoteVisualHelpers.renderEntityPreview(
            graphics = graphics,
            entityId = entityId,
            centerX = innerX + 36f,
            centerY = bodyY + 42f - userOffset * 8f,
            boxSize = 56f,
            userScale = userScale,
            rotationDeg = rot,
        )
        if (!ok) {
            SkinRenderer.drawCenteredNoShadow(graphics, font, "?", innerX + 36, bodyY + 37, s.color("text_dim", 0xFF_A293B8.toInt()))
        }

        val rx = innerX + 76
        val rw = innerW - 76
        val rl = ResourceLocation.tryParse(entityId.substringBefore('{').trim())
        val entityName = if (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
            BuiltInRegistries.ENTITY_TYPE.get(rl).description
        } else {
            Component.translatable("abadoned_greatwork.notes.canvas.no_entity")
        }
        val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(entityName, rw))
        graphics.drawString(font, trimmed, rx, bodyY + 21, s.color("paper_text", 0xFF_2B1F16.toInt()), false)

        val dim = s.color("paper_text_dim", 0xFF_6B5844.toInt())
        graphics.drawString(font, String.format(Locale.ROOT, "x%.2f", userScale), rx, bodyY + 37, dim, false)
        graphics.drawString(font, String.format(Locale.ROOT, "y%+.1f", userOffset), rx, bodyY + 57, dim, false)
    }

    private fun renderLinkPreview(graphics: GuiGraphics, s: GuiSkin, page: NotePage) {
        val font = Minecraft.getInstance().font
        val dim = s.color("paper_text_dim", 0xFF_6B5844.toInt())
        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.canvas.link_url"), innerX, bodyY + 2, dim, false)
        graphics.drawString(font, Component.translatable("abadoned_greatwork.notes.canvas.link_label"), innerX, bodyY + 32, dim, false)

        val previewY = bodyY + 64
        SkinRenderer.drawSprite(graphics, s, "paper_button", innerX + 8, previewY, innerW - 16, 18, "normal")
        val label = page.field("link_text").ifBlank { page.field("url").ifBlank { "Open Link" } }
        val trimmed = font.plainSubstrByWidth(label, innerW - 28)
        SkinRenderer.drawCenteredNoShadow(graphics, font, trimmed, innerX + innerW / 2, previewY + 5, s.color("paper_button_text", 0xFF_2B1F16.toInt()))
    }

    private fun renderEmptyPreview(graphics: GuiGraphics, s: GuiSkin) {
        val font = Minecraft.getInstance().font
        SkinRenderer.drawSprite(graphics, s, "paper_sub_panel", innerX + 10, innerY + 24, innerW - 20, 56)
        SkinRenderer.drawSprite(graphics, s, "icon.page.empty", innerX + (innerW - 12) / 2, innerY + 33, 12, 12)
        val lines = font.split(Component.translatable("abadoned_greatwork.notes.canvas.empty_tip"), innerW - 34)
        var ty = innerY + 50
        for (line in lines.take(2)) {
            graphics.drawString(font, line, innerX + 17, ty, s.color("paper_text_dim", 0xFF_6B5844.toInt()), false)
            ty += 9
        }
    }

    private fun renderIotaList(graphics: GuiGraphics, s: GuiSkin, page: NotePage, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        val listX = innerX
        val listY = bodyY + 2
        val listW = innerW
        val listH = 72
        SkinRenderer.drawSprite(graphics, s, "paper_sub_panel", listX, listY, listW, listH)

        if (page.iotas.isEmpty()) {
            val hint = Component.translatable("abadoned_greatwork.notes.canvas.iota_empty")
            SkinRenderer.drawCenteredNoShadow(graphics, font, hint, listX + listW / 2, listY + 32, s.color("paper_text_dim", 0xFF_6B5844.toInt()))
        } else {
            val registries = Minecraft.getInstance().level?.registryAccess()
            val end = minOf(page.iotas.size, iotaScroll + IOTA_VISIBLE_ROWS)
            for (idx in iotaScroll until end) {
                val rel = idx - iotaScroll
                val ry = listY + 3 + rel * 17
                val tag = page.iotas[idx]
                val color = (runCatching { iotaColor(tag, registries) }.getOrDefault(0x6B5844)) or 0xFF_000000.toInt()
                val disp = runCatching { iotaDisplay(tag, registries) }.getOrElse { Component.literal(tag.toString()) }

                graphics.fill(listX + 4, ry + 2, listX + 7, ry + 13, color)
                val trimmed = Language.getInstance().getVisualOrder(font.substrByWidth(disp, listW - 28))
                graphics.drawString(font, trimmed, listX + 10, ry + 4, s.color("paper_text", 0xFF_2B1F16.toInt()), false)

                val delX = listX + listW - 16
                val delY = ry + 1
                val hoverDel = mouseX >= delX && mouseX < delX + 12 && mouseY >= delY && mouseY < delY + 12
                if (hoverDel) {
                    graphics.fill(delX, delY, delX + 12, delY + 12, 0x33_B03030)
                }
                SkinRenderer.drawSprite(graphics, s, "icon.delete", delX, delY, 12, 12)
            }
        }

        val countLabel = "${page.iotas.size}/${NoteValidator.MAX_IOTAS_PER_PAGE}"
        graphics.drawString(font, countLabel, innerX + innerW - font.width(countLabel), bodyY + 82, s.color("paper_text_dim", 0xFF_6B5844.toInt()), false)
    }

    fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        for (w in widgets.asReversed()) {
            if (w.mouseClicked(mx, my, button)) {
                focusedWidget?.let { if (it !== w) it.isFocused = false }
                if (w is SkinTextField && w in widgets) {
                    focusedWidget = w
                    w.isFocused = true
                } else {
                    focusedWidget = null
                    w.isFocused = false
                }
                return true
            }
        }
        val page = pageSupplier()
        if (button == 0 && page.type == NotePageType.IOTA_DISPLAY && page.iotas.isNotEmpty()) {
            val listY = bodyY + 2
            val end = minOf(page.iotas.size, iotaScroll + IOTA_VISIBLE_ROWS)
            for (idx in iotaScroll until end) {
                val rel = idx - iotaScroll
                val delX = innerX + innerW - 16
                val delY = listY + 3 + rel * 17 + 1
                if (mx >= delX && mx < delX + 12 && my >= delY && my < delY + 12) {
                    val mutable = page.iotas.toMutableList()
                    mutable.removeAt(idx)
                    iotaScroll = iotaScroll.coerceIn(0, (mutable.size - IOTA_VISIBLE_ROWS).coerceAtLeast(0))
                    updatePage(page.copy(iotas = mutable), rebuild = true)
                    return true
                }
            }
        }
        if (button == 0 && previewHoverRect?.contains(mx, my) == true) {
            when (page.type) {
                NotePageType.HEX_MANUAL_PATTERN -> {
                    openDrawPatternForCurrent(addNew = page.patterns.isEmpty())
                    return true
                }
                NotePageType.HEX_PATTERN -> {
                    openModal(
                        ActionPickerModal(onDismiss = {}) { pickedAction ->
                            updatePage(pageSupplier().withField("op_id", pickedAction), rebuild = false)
                        }
                    )
                    return true
                }
                else -> {}
            }
        }
        focusedWidget?.isFocused = false
        focusedWidget = null
        return false
    }

    fun mouseReleased(mx: Double, my: Double, button: Int): Boolean =
        widgets.asReversed().any { it.mouseReleased(mx, my, button) }

    fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean =
        widgets.asReversed().any { it.mouseDragged(mx, my, button, dx, dy) }

    fun mouseScrolled(mx: Double, my: Double, delta: Double): Boolean {
        val page = pageSupplier()
        if (page.type == NotePageType.IOTA_DISPLAY && mx >= innerX && mx < innerX + innerW && my >= bodyY && my < bodyY + 76) {
            val maxScroll = (page.iotas.size - IOTA_VISIBLE_ROWS).coerceAtLeast(0)
            if (maxScroll > 0) {
                iotaScroll = (iotaScroll - delta.toInt().coerceIn(-1, 1)).coerceIn(0, maxScroll)
                return true
            }
        }
        return false
    }

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focusedWidget?.keyPressed(keyCode, scanCode, modifiers) == true

    fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        focusedWidget?.charTyped(codePoint, modifiers) == true

    fun hasFocusedTextInput(): Boolean = focusedWidget != null && focusedWidget!!.isFocused

    fun clearFocus() {
        focusedWidget?.isFocused = false
        focusedWidget = null
    }

    companion object {
        private const val IOTA_VISIBLE_ROWS = 4

        fun pageTextCapacityLines(page: NotePage, isFirstPage: Boolean): Int = when (page.type) {
            NotePageType.TEXT -> when {
                isFirstPage -> 14
                page.title.isNotBlank() -> 16
                else -> 17
            }
            NotePageType.SPOTLIGHT -> 10
            NotePageType.CRAFTING -> if (page.field("recipe2").isNotBlank()) 0 else 10
            NotePageType.SMELTING -> if (page.field("recipe2").isNotBlank()) 8 else 14
            NotePageType.HEX_PATTERN, NotePageType.HEX_MANUAL_PATTERN ->
                if (page.field("input").isNotBlank() || page.field("output").isNotBlank()) 7 else 8
            NotePageType.IMAGE -> 4
            NotePageType.ENTITY -> 4
            NotePageType.LINK -> when {
                isFirstPage -> 11
                page.title.isNotBlank() -> 12
                else -> 13
            }
            NotePageType.EMPTY -> 0
            NotePageType.IOTA_DISPLAY -> 7
        }

        private fun parsePattern(def: ManualPatternDef): HexPattern? {
            if (def.signature.isBlank()) return null
            val dir = runCatching { HexDir.fromString(def.startDir.uppercase(Locale.ROOT)) }.getOrNull() ?: HexDir.EAST
            return runCatching { patternFromAngles(def.signature, dir) }.getOrNull()
        }

        private fun pickCycleStack(ingredient: Ingredient, tick: Int): ItemStack {
            val items = ingredient.items
            if (items.isEmpty()) return ItemStack.EMPTY
            return items[Math.floorMod(tick, items.size)]
        }

        private fun resolveRecipeOutputName(recipeId: String, type: RecipeType<*>): String {
            val level = Minecraft.getInstance().level ?: return recipeId
            val rl = ResourceLocation.tryParse(recipeId.trim()) ?: return recipeId
            val recipe: Recipe<*>? = level.recipeManager.byKey(rl).orElse(null)?.value
            if (recipe != null && recipe.type == type) {
                val out = recipe.getResultItem(level.registryAccess())
                if (!out.isEmpty) return out.hoverName.string
            }
            return rl.path
        }
    }
}
