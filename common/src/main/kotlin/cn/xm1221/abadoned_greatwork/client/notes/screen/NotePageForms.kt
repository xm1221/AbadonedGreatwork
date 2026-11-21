package cn.xm1221.abadoned_greatwork.client.notes.screen

import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.item.IotaHolderItem
import at.petrak.hexcasting.client.render.PatternColors
import at.petrak.hexcasting.client.render.PatternRenderer
import at.petrak.hexcasting.client.render.PatternSettings
import at.petrak.hexcasting.common.lib.hex.HexActions
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkin
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinButton
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinRenderer
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinSuggestBox
import cn.xm1221.abadoned_greatwork.client.gui.skin.SkinTextField
import cn.xm1221.abadoned_greatwork.client.gui.skin.SuggestOption
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.ManualPatternDef
import cn.xm1221.abadoned_greatwork.notes.NotePage
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.crafting.RecipeType

/**
 * 为 [NoteEditorScreen] 构建 11 种书页类型的表单字段与交互渲染。
 */
class NotePageFormHost(
    private val skinSupplier: () -> GuiSkin,
    private val suggestBox: SkinSuggestBox,
    private val addWidget: (AbstractWidget) -> Unit,
    private val getPage: () -> NotePage,
    private val setPage: (NotePage) -> Unit,
    private val rebuild: () -> Unit,
) {
    private val labels = mutableListOf<FormLabel>()
    private var patternPreviewBox: PreviewBox? = null
    private var manualPatIdx: Int = 0
    private var imageIdx: Int = 0
    private var iotaStatusText: Component? = null

    private data class FormLabel(val x: Int, val y: Int, val text: Component)
    private data class PreviewBox(val x: Int, val y: Int, val w: Int, val h: Int)

    fun build(fx: Int, fy: Int, fw: Int, fh: Int) {
        labels.clear()
        patternPreviewBox = null
        val page = getPage()
        val font = Minecraft.getInstance().font
        var cy = fy + 22 // 留出顶部书页类型切换按钮
        val innerW = fw - 12
        val lx = fx + 6

        fun addLabeledField(
            labelKey: String,
            initial: String,
            maxLen: Int = 256,
            withSearch: ((SkinTextField, Int, Int) -> Unit)? = null,
            onChange: (String) -> Unit,
        ): SkinTextField {
            labels.add(FormLabel(lx, cy, Component.translatable(labelKey)))
            cy += 10
            val fieldW = if (withSearch != null) innerW - 18 else innerW
            val field = SkinTextField(skinSupplier, font, lx, cy, fieldW, 14, Component.translatable(labelKey))
            field.setMaxLength(maxLen)
            field.value = initial
            field.setResponder { value ->
                onChange(value)
                if (suggestBox.open) suggestBox.updateFilter(value)
            }
            addWidget(field)
            if (withSearch != null) {
                val bx = lx + fieldW + 2
                val by = cy
                addWidget(
                    SkinButton(skinSupplier, bx, by, 16, 14, Component.empty(), "button", "icon.search") {
                        withSearch(field, lx, by + 16)
                    }
                )
            }
            cy += 17
            return field
        }

        fun addToggle(labelKey: String, current: Boolean, onToggle: (Boolean) -> Unit) {
            val stateText = Component.translatable(labelKey).string + ": " +
                Component.translatable(if (current) "abadoned_greatwork.notes.on" else "abadoned_greatwork.notes.off").string
            addWidget(
                SkinButton(skinSupplier, lx, cy, innerW, 14, Component.literal(stateText), "button") {
                    onToggle(!current)
                    rebuild()
                }
            )
            cy += 17
        }

        when (page.type) {
            NotePageType.TEXT -> {
                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.SPOTLIGHT -> {
                val itemField = addLabeledField(
                    "abadoned_greatwork.notes.field.item",
                    page.field("item", "minecraft:paper"),
                    128,
                    withSearch = { f, sx, sy -> openItemSuggest(sx, sy, f) },
                ) { v ->
                    setPage(getPage().withField("item", v))
                }
                addWidget(
                    SkinButton(skinSupplier, lx, cy, innerW, 14, Component.translatable("abadoned_greatwork.notes.btn.from_hand"), "button") {
                        pickHeldItemId()?.let { id ->
                            itemField.value = id
                            setPage(getPage().withField("item", id))
                        }
                    }
                )
                cy += 17
                addToggle("abadoned_greatwork.notes.field.link_recipe", page.boolField("link_recipe", false)) { next ->
                    setPage(getPage().withField("link_recipe", next.toString()))
                }
                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.CRAFTING, NotePageType.SMELTING -> {
                val rType = if (page.type == NotePageType.SMELTING) RecipeType.SMELTING else RecipeType.CRAFTING
                addLabeledField(
                    "abadoned_greatwork.notes.field.recipe",
                    page.field("recipe"),
                    128,
                    withSearch = { f, sx, sy -> openRecipeSuggest(rType, sx, sy, f) { v -> setPage(getPage().withField("recipe", v)) } },
                ) { v ->
                    setPage(getPage().withField("recipe", v))
                }
                addLabeledField(
                    "abadoned_greatwork.notes.field.recipe2",
                    page.field("recipe2"),
                    128,
                    withSearch = { f, sx, sy -> openRecipeSuggest(rType, sx, sy, f) { v -> setPage(getPage().withField("recipe2", v)) } },
                ) { v ->
                    setPage(getPage().withField("recipe2", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.HEX_PATTERN -> {
                addLabeledField(
                    "abadoned_greatwork.notes.field.op_id",
                    page.field("op_id"),
                    128,
                    withSearch = { f, sx, sy -> openActionSuggest(sx, sy, f) },
                ) { v ->
                    setPage(getPage().withField("op_id", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.input", page.field("input"), 64) { v ->
                    setPage(getPage().withField("input", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.output", page.field("output"), 64) { v ->
                    setPage(getPage().withField("output", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.header_optional", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.HEX_MANUAL_PATTERN -> {
                val pats = page.patterns.ifEmpty { listOf(ManualPatternDef("EAST", "qaq")) }
                if (pats != page.patterns) {
                    setPage(page.copy(patterns = pats))
                }
                manualPatIdx = manualPatIdx.coerceIn(0, pats.lastIndex)
                val curPat = pats[manualPatIdx]

                // 图案序号与增删
                addWidget(
                    SkinButton(skinSupplier, lx, cy, 18, 14, Component.literal("<"), "button") {
                        manualPatIdx = (manualPatIdx - 1).coerceAtLeast(0)
                        rebuild()
                    }.apply { active = manualPatIdx > 0 }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 20, cy, 18, 14, Component.literal(">"), "button") {
                        manualPatIdx = (manualPatIdx + 1).coerceAtMost(pats.lastIndex)
                        rebuild()
                    }.apply { active = manualPatIdx < pats.lastIndex }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 40, cy, 38, 14, Component.literal("${manualPatIdx + 1}/${pats.size}"), "button") {}
                        .apply { active = false }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 80, cy, 16, 14, Component.empty(), "button", "icon.add") {
                        val nextList = getPage().patterns.toMutableList()
                        nextList.add(ManualPatternDef("EAST", ""))
                        manualPatIdx = nextList.lastIndex
                        setPage(getPage().copy(patterns = nextList))
                        rebuild()
                    }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 98, cy, 16, 14, Component.empty(), "button_danger", "icon.delete") {
                        val nextList = getPage().patterns.toMutableList()
                        if (nextList.size > 1) {
                            nextList.removeAt(manualPatIdx)
                            manualPatIdx = manualPatIdx.coerceIn(0, nextList.lastIndex)
                            setPage(getPage().copy(patterns = nextList))
                            rebuild()
                        }
                    }.apply { active = pats.size > 1 }
                )
                cy += 16

                // 起始方向循环按钮
                addWidget(
                    SkinButton(skinSupplier, lx, cy, innerW, 14, Component.literal("Dir: ${curPat.startDir}"), "button") {
                        val dirs = HexDir.entries
                        val cur = dirs.indexOfFirst { it.name == curPat.startDir }.let { if (it < 0) 0 else it }
                        val nextDir = dirs[(cur + 1) % dirs.size].name
                        val nextList = getPage().patterns.toMutableList()
                        nextList[manualPatIdx] = nextList[manualPatIdx].copy(startDir = nextDir)
                        setPage(getPage().copy(patterns = nextList))
                        rebuild()
                    }
                )
                cy += 16

                // 签名输入
                addLabeledField("abadoned_greatwork.notes.field.signature", curPat.signature, 64) { sig ->
                    val cleanSig = sig.lowercase().filter { it in "qweasd" }
                    val nextList = getPage().patterns.toMutableList()
                    if (manualPatIdx in nextList.indices) {
                        nextList[manualPatIdx] = nextList[manualPatIdx].copy(signature = cleanSig)
                        setPage(getPage().copy(patterns = nextList))
                    }
                }

                // 实时图案预览框 + io
                val boxH = (fy + fh - cy - 36).coerceIn(26, 46)
                patternPreviewBox = PreviewBox(lx, cy, 46, boxH)
                val ioX = lx + 50
                val ioW = (innerW - 50).coerceAtLeast(36)
                val inField = SkinTextField(skinSupplier, font, ioX, cy, ioW, 14, Component.literal("in"))
                inField.value = page.field("input")
                inField.setHint(Component.literal("in"))
                inField.setResponder { setPage(getPage().withField("input", it)) }
                addWidget(inField)

                val outField = SkinTextField(skinSupplier, font, ioX, cy + 16, ioW, 14, Component.literal("out"))
                outField.value = page.field("output")
                outField.setHint(Component.literal("out"))
                outField.setResponder { setPage(getPage().withField("output", it)) }
                addWidget(outField)
                cy += maxOf(boxH, 32) + 2

                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.IMAGE -> {
                val imgs = page.images.ifEmpty { listOf("") }
                if (imgs != page.images) {
                    setPage(page.copy(images = imgs))
                }
                imageIdx = imageIdx.coerceIn(0, imgs.lastIndex)
                addWidget(
                    SkinButton(skinSupplier, lx, cy, 20, 14, Component.literal("<"), "button") {
                        imageIdx = (imageIdx - 1).coerceAtLeast(0)
                        rebuild()
                    }.apply { active = imageIdx > 0 }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 22, cy, 20, 14, Component.literal(">"), "button") {
                        imageIdx = (imageIdx + 1).coerceAtMost(imgs.lastIndex)
                        rebuild()
                    }.apply { active = imageIdx < imgs.lastIndex }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 44, cy, 38, 14, Component.literal("${imageIdx + 1}/${imgs.size}"), "button") {}
                        .apply { active = false }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 84, cy, 16, 14, Component.empty(), "button", "icon.add") {
                        val next = getPage().images.toMutableList()
                        next.add("")
                        imageIdx = next.lastIndex
                        setPage(getPage().copy(images = next))
                        rebuild()
                    }.apply { active = imgs.size < NoteValidator.MAX_IMAGES_PER_PAGE }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + 102, cy, 16, 14, Component.empty(), "button_danger", "icon.delete") {
                        val next = getPage().images.toMutableList()
                        if (next.size > 1) {
                            next.removeAt(imageIdx)
                            imageIdx = imageIdx.coerceIn(0, next.lastIndex)
                            setPage(getPage().copy(images = next))
                            rebuild()
                        }
                    }.apply { active = imgs.size > 1 }
                )
                cy += 17

                addLabeledField("abadoned_greatwork.notes.field.image_rl", imgs[imageIdx], 160) { v ->
                    val next = getPage().images.toMutableList()
                    if (imageIdx in next.indices) {
                        next[imageIdx] = v
                        setPage(getPage().copy(images = next))
                    }
                }
                addToggle("abadoned_greatwork.notes.field.border", page.boolField("border", true)) { next ->
                    setPage(getPage().withField("border", next.toString()))
                }
                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.ENTITY -> {
                addLabeledField(
                    "abadoned_greatwork.notes.field.entity",
                    page.field("entity", "minecraft:allay"),
                    128,
                    withSearch = { f, sx, sy -> openEntitySuggest(sx, sy, f) },
                ) { v ->
                    setPage(getPage().withField("entity", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.scale", page.field("scale", "1.0"), 12) { v ->
                    setPage(getPage().withField("scale", v))
                }
                addToggle("abadoned_greatwork.notes.field.rotate", page.boolField("rotate", true)) { next ->
                    setPage(getPage().withField("rotate", next.toString()))
                }
                addLabeledField("abadoned_greatwork.notes.field.entity_name", page.field("name"), 64) { v ->
                    setPage(getPage().withField("name", v))
                }
            }

            NotePageType.LINK -> {
                addLabeledField("abadoned_greatwork.notes.field.url", page.field("url", "https://"), 256) { v ->
                    setPage(getPage().withField("url", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.link_text", page.field("link_text"), 64) { v ->
                    setPage(getPage().withField("link_text", v))
                }
                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
            }

            NotePageType.EMPTY -> {
                addToggle("abadoned_greatwork.notes.field.draw_filler", page.boolField("draw_filler", true)) { next ->
                    setPage(getPage().withField("draw_filler", next.toString()))
                }
            }

            NotePageType.IOTA_DISPLAY -> {
                addLabeledField("abadoned_greatwork.notes.field.page_title", page.title, 64) { v ->
                    setPage(getPage().copy(title = v))
                }
                addWidget(
                    SkinButton(skinSupplier, lx, cy, innerW, 14, Component.translatable("abadoned_greatwork.notes.btn.read_iota_hand"), "button_accent") {
                        val added = importIotasFromPlayerHands()
                        iotaStatusText = if (added > 0) {
                            Component.translatable("abadoned_greatwork.notes.iota_added", added)
                        } else {
                            Component.translatable("abadoned_greatwork.notes.iota_none_in_hand")
                        }
                        rebuild()
                    }
                )
                cy += 17
                val half = (innerW - 4) / 2
                addWidget(
                    SkinButton(skinSupplier, lx, cy, half, 14, Component.translatable("abadoned_greatwork.notes.btn.pop_iota"), "button") {
                        val cur = getPage().iotas
                        if (cur.isNotEmpty()) {
                            setPage(getPage().copy(iotas = cur.dropLast(1)))
                            rebuild()
                        }
                    }.apply { active = page.iotas.isNotEmpty() }
                )
                addWidget(
                    SkinButton(skinSupplier, lx + half + 4, cy, half, 14, Component.translatable("abadoned_greatwork.notes.btn.clear_iotas"), "button_danger") {
                        setPage(getPage().copy(iotas = emptyList()))
                        rebuild()
                    }.apply { active = page.iotas.isNotEmpty() }
                )
                cy += 17
                addLabeledField("abadoned_greatwork.notes.field.snbt_paste", "", 512) { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        val parsed = runCatching { TagParser.parseTag(trimmed) }.getOrNull()
                        if (parsed != null && IotaType.getTypeFromTag(parsed) != null) {
                            val next = (getPage().iotas + parsed).take(NoteValidator.MAX_IOTAS_PER_PAGE)
                            setPage(getPage().copy(iotas = next))
                            iotaStatusText = Component.translatable("abadoned_greatwork.notes.iota_added", 1)
                        }
                    }
                }
            }
        }
    }

    fun renderCustomOverlays(graphics: GuiGraphics, fx: Int, fy: Int, fw: Int, fh: Int) {
        val skin = skinSupplier()
        val font = Minecraft.getInstance().font
        val labelColor = skin.color("text_dim", 0xFF_9E8EB8.toInt())
        for (lbl in labels) {
            graphics.drawString(font, lbl.text, lbl.x, lbl.y, labelColor, false)
        }

        val page = getPage()
        if (page.type == NotePageType.HEX_MANUAL_PATTERN) {
            patternPreviewBox?.let { box ->
                SkinRenderer.drawSprite(graphics, skin, "slot_frame", box.x, box.y, box.w, box.h)
                val patDef = page.patterns.getOrNull(manualPatIdx)
                if (patDef != null && patDef.signature.isNotEmpty()) {
                    val pat = runCatching {
                        HexPattern.fromAngles(patDef.signature, HexDir.fromString(patDef.startDir))
                    }.getOrNull()
                    if (pat != null) {
                        val patSets = PatternSettings(
                            "abw_form_preview",
                            PatternSettings.PositionSettings(
                                (box.w - 4).toDouble(),
                                (box.h - 4).toDouble(),
                                2.0,
                                2.0,
                                PatternSettings.AxisAlignment.CENTER_FIT,
                                PatternSettings.AxisAlignment.CENTER_FIT,
                                12.0,
                                0.0,
                                0.0,
                            ),
                            PatternSettings.StrokeSettings.fromStroke(3.0),
                            PatternSettings.ZappySettings.STATIC,
                        )
                        graphics.pose().pushPose()
                        graphics.pose().translate((box.x + 2).toFloat(), (box.y + 2).toFloat(), 50f)
                        PatternRenderer.renderPattern(pat, graphics.pose(), patSets, PatternColors.READABLE_GRID_SCROLL_COLORS, 0.0, 3)
                        graphics.pose().popPose()
                    }
                }
            }
        } else if (page.type == NotePageType.IMAGE) {
            val imgStr = page.images.getOrNull(imageIdx).orEmpty().trim()
            val rl = ResourceLocation.tryParse(imgStr)
            val exists = rl != null && Minecraft.getInstance().resourceManager.getResource(rl).isPresent
            val badge = Component.translatable(
                if (exists) "abadoned_greatwork.notes.image_exists" else "abadoned_greatwork.notes.image_missing"
            )
            val col = if (exists) skin.color("ok", 0xFF_78E092.toInt()) else skin.color("error", 0xFF_EC6270.toInt())
            graphics.drawString(font, badge, fx + 6, fy + fh - 14, col, false)
        } else if (page.type == NotePageType.IOTA_DISPLAY) {
            val listY = fy + fh - 44
            val countLine = Component.translatable("abadoned_greatwork.notes.iota_count", page.iotas.size)
            graphics.drawString(font, countLine, fx + 6, listY, skin.color("text", 0xFF_EDE4FA.toInt()), false)
            if (page.iotas.isNotEmpty()) {
                val topTag = page.iotas.last()
                val disp: Component = runCatching { IotaType.getDisplay(topTag) }.getOrElse { Component.literal("?") }
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(disp, fw - 14)), fx + 6, listY + 11, skin.color("text_dim", 0xFF_9E8EB8.toInt()), false)
            }
            iotaStatusText?.let { st ->
                graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(st, fw - 14)), fx + 6, listY + 23, skin.color("ok", 0xFF_78E092.toInt()), false)
            }
        }
    }

    private fun pickHeldItemId(): String? {
        val player = Minecraft.getInstance().player ?: return null
        val off = player.offhandItem
        val stack = if (!off.isEmpty) off else player.mainHandItem
        if (stack.isEmpty) return null
        return BuiltInRegistries.ITEM.getKey(stack.item).toString()
    }

    private fun importIotasFromPlayerHands(): Int {
        val player = Minecraft.getInstance().player ?: return 0
        val candidates = listOf(player.offhandItem, player.mainHandItem) +
            (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }
        for (stack in candidates) {
            val item = stack.item
            if (item is IotaHolderItem) {
                val rawTag = item.readIotaTag(stack) ?: continue
                val extracted = extractTagOnlyIotas(rawTag)
                if (extracted.isNotEmpty()) {
                    val merged = (getPage().iotas + extracted).take(NoteValidator.MAX_IOTAS_PER_PAGE)
                    setPage(getPage().copy(iotas = merged))
                    return extracted.size
                }
            }
        }
        return 0
    }

    /** 严格 tag-only 展开 ListIota 或单个 iota tag，不触发任何客户端反序列化 */
    private fun extractTagOnlyIotas(tag: CompoundTag): List<CompoundTag> {
        val type = IotaType.getTypeFromTag(tag)
        if (type == HexIotaTypes.LIST) {
            val data = tag.get(HexIotaTypes.KEY_DATA)
            if (data is ListTag) {
                val out = mutableListOf<CompoundTag>()
                for (i in 0 until data.size) {
                    val elem = data.get(i)
                    if (elem is CompoundTag) out.add(elem.copy())
                }
                return out
            }
        }
        return listOf(tag.copy())
    }

    private fun openItemSuggest(sx: Int, sy: Int, field: SkinTextField) {
        val options = BuiltInRegistries.ITEM.keySet().sortedBy { it.toString() }.map { rl ->
            val stack = BuiltInRegistries.ITEM.get(rl).defaultInstance
            SuggestOption(rl.toString(), stack.hoverName.string)
        }
        suggestBox.show(sx, sy.coerceAtMost(140), 170, 96, options, field.value) { opt ->
            field.value = opt.value
            setPage(getPage().withField("item", opt.value))
        }
    }

    private fun openEntitySuggest(sx: Int, sy: Int, field: SkinTextField) {
        val options = BuiltInRegistries.ENTITY_TYPE.keySet().sortedBy { it.toString() }.map { rl ->
            val name = BuiltInRegistries.ENTITY_TYPE.get(rl).description.string
            SuggestOption(rl.toString(), name)
        }
        suggestBox.show(sx, sy.coerceAtMost(140), 170, 96, options, field.value) { opt ->
            field.value = opt.value
            setPage(getPage().withField("entity", opt.value))
        }
    }

    private fun openRecipeSuggest(
        recipeType: RecipeType<*>,
        sx: Int,
        sy: Int,
        field: SkinTextField,
        applyValue: (String) -> Unit,
    ) {
        val level = Minecraft.getInstance().level ?: return
        val options = level.recipeManager.recipes
            .filter { it.type == recipeType }
            .map { r ->
                val outName = r.getResultItem(level.registryAccess()).hoverName.string
                SuggestOption(r.id.toString(), outName)
            }
            .sortedBy { it.value }
        suggestBox.show(sx, sy.coerceAtMost(140), 180, 96, options, field.value) { opt ->
            field.value = opt.value
            applyValue(opt.value)
        }
    }

    private fun openActionSuggest(sx: Int, sy: Int, field: SkinTextField) {
        val excluded = ClientNoteLibrary.excludedActions
        val options = HexActions.REGISTRY.keySet()
            .filter { it.toString() !in excluded }
            .map { rl ->
                val key = "hexcasting.action.$rl"
                val label = if (I18n.exists(key)) I18n.get(key) else rl.toString()
                SuggestOption(rl.toString(), label)
            }
            .sortedBy { it.label }
        suggestBox.show(sx, sy.coerceAtMost(140), 180, 96, options, field.value) { opt ->
            field.value = opt.value
            setPage(getPage().withField("op_id", opt.value))
        }
    }
}
