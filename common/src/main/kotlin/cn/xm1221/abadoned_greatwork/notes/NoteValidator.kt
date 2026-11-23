package cn.xm1221.abadoned_greatwork.notes

import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * 玩家笔记校验器：负责宏指令剔除、体积限制、字段白名单与服务端注册表校验。
 */
object NoteValidator {

    const val MAX_SERIALIZED_BYTES = 30_000
    const val MAX_TITLE_LENGTH = 64
    const val MAX_FIELD_LENGTH = 256
    const val MAX_IMAGES_PER_PAGE = 8
    const val MAX_PATTERNS_PER_PAGE = 16
    const val MAX_IOTAS_PER_PAGE = 16

    /** 剔除可点击执行命令的 Patchouli 宏 `$(c:...)` / `$(cmd:...)` */
    private val CMD_MACRO_REGEX = Regex("""\$\((?:c|cmd):[^)]*\)""", RegexOption.IGNORE_CASE)
    private val ANGLE_SIGNATURE_REGEX = Regex("^[qweasd]*$")
    private val HEX_COLOR_REGEX = Regex("^[0-9a-fA-F]{6}$")

    data class Limits(
        val maxEntriesPerPlayer: Int = 128,
        val maxPagesPerEntry: Int = 32,
        val maxTextPerPage: Int = 4096,
        val allowExternalLinks: Boolean = true,
    )

    sealed class ValidationResult {
        data class Ok(val entry: NoteEntry) : ValidationResult()
        data class Err(
            val messageKey: String,
            val args: List<String> = emptyList(),
            val pageIndex: Int? = null,
        ) : ValidationResult()
    }

    fun currentLimits(): Limits {
        val cfg = runCatching { Abadoned_greatworkServerConfig.config }.getOrNull()
        return if (cfg != null) {
            Limits(
                maxEntriesPerPlayer = cfg.maxEntriesPerPlayer.coerceIn(1, 512),
                maxPagesPerEntry = cfg.maxPagesPerEntry.coerceIn(1, 128),
                maxTextPerPage = cfg.maxTextPerPage.coerceIn(128, 16384),
                allowExternalLinks = cfg.allowExternalLinks,
            )
        } else {
            Limits()
        }
    }

    /** 清除可能执行命令的 Patchouli 文本宏，并裁剪至最大长度 */
    fun sanitizeText(raw: String, maxLength: Int = 4096): String {
        val cleaned = raw.replace(CMD_MACRO_REGEX, "")
        return if (cleaned.length > maxLength) cleaned.substring(0, maxLength) else cleaned
    }

    /** 仅做与 MC 注册表无关的清洗与结构校验（客户端与单元测试通用） */
    fun validateStructure(entry: NoteEntry, limits: Limits = currentLimits()): ValidationResult {
        if (entry.pages.isEmpty()) {
            return ValidationResult.Err("abadoned_greatwork.notes.error.empty_pages")
        }
        if (entry.pages.size > limits.maxPagesPerEntry) {
            return ValidationResult.Err(
                "abadoned_greatwork.notes.error.too_many_pages",
                listOf(limits.maxPagesPerEntry.toString()),
            )
        }

        val cleanId = entry.id.trim().take(64).ifBlank { UUID.randomUUID().toString() }
        val cleanTitle = sanitizeText(entry.title.trim(), MAX_TITLE_LENGTH)
        val cleanAuthor = entry.author.trim().take(32)
        val rawIcon = entry.icon.trim()
        val cleanIcon = if (rawIcon.isBlank() || ResourceLocation.tryParse(rawIcon) == null) {
            NoteEntry.DEFAULT_ICON
        } else {
            rawIcon
        }
        val cleanColor = entry.color.trim().removePrefix("#").let {
            if (HEX_COLOR_REGEX.matches(it)) it.lowercase() else NoteEntry.DEFAULT_COLOR
        }

        val cleanPages = mutableListOf<NotePage>()
        for ((index, page) in entry.pages.withIndex()) {
            val res = sanitizeAndValidatePageSyntax(page, index, limits)
            if (res is PageValidation.Err) {
                return ValidationResult.Err(res.messageKey, res.args, pageIndex = index)
            }
            cleanPages.add((res as PageValidation.Ok).page)
        }

        val sanitized = entry.copy(
            id = cleanId,
            title = cleanTitle,
            author = cleanAuthor,
            icon = cleanIcon,
            color = cleanColor,
            pages = cleanPages,
        )

        val byteSize = estimateNbtBytes(sanitized.toTag())
        if (byteSize > MAX_SERIALIZED_BYTES) {
            return ValidationResult.Err(
                "abadoned_greatwork.notes.error.too_large",
                listOf((byteSize / 1024).toString(), (MAX_SERIALIZED_BYTES / 1024).toString()),
            )
        }
        return ValidationResult.Ok(sanitized)
    }

    /** 服务端完整校验：在结构校验基础上验证物品/实体/配方/图案注册表和 per-world 限制 */
    fun validateServer(
        entry: NoteEntry,
        level: ServerLevel,
        limits: Limits = currentLimits(),
    ): ValidationResult {
        val struct = validateStructure(entry, limits)
        if (struct is ValidationResult.Err) return struct
        val clean = (struct as ValidationResult.Ok).entry

        // 图标物品回退校验
        val iconId = ResourceLocation.tryParse(clean.icon)
        val safeIcon = if (iconId != null && BuiltInRegistries.ITEM.containsKey(iconId)) {
            iconId.toString()
        } else {
            NoteEntry.DEFAULT_ICON
        }

        val perWorldTag = HexActions.REGISTRY.getTag(HexTags.Actions.PER_WORLD_PATTERN)

        for ((index, page) in clean.pages.withIndex()) {
            val pageNum = (index + 1).toString()
            when (page.type) {
                NotePageType.SPOTLIGHT -> {
                    val itemStr = page.field("item")
                    val rl = ResourceLocation.tryParse(itemStr)
                    if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                        return ValidationResult.Err("abadoned_greatwork.notes.error.bad_item", listOf(pageNum, itemStr), pageIndex = index)
                    }
                }
                NotePageType.CRAFTING, NotePageType.SMELTING -> {
                    val r1 = ResourceLocation.tryParse(page.field("recipe"))
                    if (r1 == null || level.recipeManager.byKey(r1).isEmpty) {
                        return ValidationResult.Err("abadoned_greatwork.notes.error.bad_recipe", listOf(pageNum, page.field("recipe")), pageIndex = index)
                    }
                    val r2Str = page.field("recipe2")
                    if (r2Str.isNotEmpty()) {
                        val r2 = ResourceLocation.tryParse(r2Str)
                        if (r2 == null || level.recipeManager.byKey(r2).isEmpty) {
                            return ValidationResult.Err("abadoned_greatwork.notes.error.bad_recipe", listOf(pageNum, r2Str), pageIndex = index)
                        }
                    }
                }
                NotePageType.HEX_PATTERN -> {
                    val opStr = page.field("op_id")
                    val opId = ResourceLocation.tryParse(opStr)
                    if (opId == null || !HexActions.REGISTRY.containsKey(opId)) {
                        return ValidationResult.Err("abadoned_greatwork.notes.error.bad_action", listOf(pageNum, opStr), pageIndex = index)
                    }
                    val key = ResourceKey.create(HexActions.REGISTRY.key(), opId)
                    val isPerWorld = perWorldTag.isPresent && perWorldTag.get().stream().anyMatch { it.`is`(key) }
                    if (isPerWorld) {
                        return ValidationResult.Err("abadoned_greatwork.notes.error.per_world_forbidden", listOf(pageNum, opStr), pageIndex = index)
                    }
                }
                NotePageType.ENTITY -> {
                    val entStr = page.field("entity")
                    val rl = ResourceLocation.tryParse(entStr)
                    if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                        return ValidationResult.Err("abadoned_greatwork.notes.error.bad_entity", listOf(pageNum, entStr), pageIndex = index)
                    }
                }
                else -> {}
            }
        }

        return ValidationResult.Ok(clean.copy(icon = safeIcon))
    }

    private sealed class PageValidation {
        data class Ok(val page: NotePage) : PageValidation()
        data class Err(val messageKey: String, val args: List<String>) : PageValidation()
    }

    private fun sanitizeAndValidatePageSyntax(
        page: NotePage,
        index: Int,
        limits: Limits,
    ): PageValidation {
        val pageNum = (index + 1).toString()
        val title = sanitizeText(page.title.trim(), MAX_TITLE_LENGTH)
        val text = sanitizeText(page.text, limits.maxTextPerPage)

        fun cleanField(key: String, maxLen: Int = MAX_FIELD_LENGTH): String =
            sanitizeText(page.field(key).trim(), maxLen)

        return when (page.type) {
            NotePageType.TEXT -> PageValidation.Ok(
                NotePage(type = NotePageType.TEXT, title = title, text = text)
            )
            NotePageType.SPOTLIGHT -> {
                val item = cleanField("item")
                if (item.isEmpty() || ResourceLocation.tryParse(item) == null) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_item", listOf(pageNum, item))
                }
                val linkRecipe = page.boolField("link_recipe", false).toString()
                PageValidation.Ok(
                    NotePage(
                        type = NotePageType.SPOTLIGHT,
                        title = title,
                        text = text,
                        fields = mapOf("item" to item, "link_recipe" to linkRecipe),
                    )
                )
            }
            NotePageType.CRAFTING, NotePageType.SMELTING -> {
                val r1 = cleanField("recipe")
                if (r1.isEmpty() || ResourceLocation.tryParse(r1) == null) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_recipe", listOf(pageNum, r1))
                }
                val r2 = cleanField("recipe2")
                if (r2.isNotEmpty() && ResourceLocation.tryParse(r2) == null) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_recipe", listOf(pageNum, r2))
                }
                val fields = buildMap {
                    put("recipe", r1)
                    if (r2.isNotEmpty()) put("recipe2", r2)
                }
                PageValidation.Ok(NotePage(type = page.type, title = title, text = text, fields = fields))
            }
            NotePageType.HEX_PATTERN -> {
                val opId = cleanField("op_id")
                if (opId.isEmpty() || ResourceLocation.tryParse(opId) == null) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_action", listOf(pageNum, opId))
                }
                val fields = buildMap {
                    put("op_id", opId)
                    cleanField("input", 128).takeIf { it.isNotEmpty() }?.let { put("input", it) }
                    cleanField("output", 128).takeIf { it.isNotEmpty() }?.let { put("output", it) }
                }
                PageValidation.Ok(NotePage(type = NotePageType.HEX_PATTERN, title = title, text = text, fields = fields))
            }
            NotePageType.HEX_MANUAL_PATTERN -> {
                if (page.patterns.isEmpty()) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.empty_patterns", listOf(pageNum))
                }
                if (page.patterns.size > MAX_PATTERNS_PER_PAGE) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.too_many_patterns", listOf(pageNum))
                }
                val cleanPats = mutableListOf<ManualPatternDef>()
                for (pat in page.patterns) {
                    val dir = runCatching { HexDir.fromString(pat.startDir.trim().uppercase()).name }.getOrNull()
                        ?: return PageValidation.Err("abadoned_greatwork.notes.error.bad_pattern", listOf(pageNum, pat.startDir))
                    val sig = pat.signature.trim().lowercase()
                    if (!ANGLE_SIGNATURE_REGEX.matches(sig)) {
                        return PageValidation.Err("abadoned_greatwork.notes.error.bad_pattern", listOf(pageNum, pat.signature))
                    }
                    cleanPats.add(ManualPatternDef(dir, sig))
                }
                val fields = buildMap {
                    cleanField("input", 128).takeIf { it.isNotEmpty() }?.let { put("input", it) }
                    cleanField("output", 128).takeIf { it.isNotEmpty() }?.let { put("output", it) }
                    put("stroke_order", page.boolField("stroke_order", true).toString())
                }
                PageValidation.Ok(
                    NotePage(
                        type = NotePageType.HEX_MANUAL_PATTERN,
                        title = title,
                        text = text,
                        fields = fields,
                        patterns = cleanPats,
                    )
                )
            }
            NotePageType.IMAGE -> {
                val imgs = page.images.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_IMAGES_PER_PAGE)
                if (imgs.isEmpty() || imgs.any { ResourceLocation.tryParse(it) == null }) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_image", listOf(pageNum))
                }
                val fields = mapOf("border" to page.boolField("border", true).toString())
                PageValidation.Ok(
                    NotePage(
                        type = NotePageType.IMAGE,
                        title = title,
                        text = text,
                        fields = fields,
                        images = imgs,
                    )
                )
            }
            NotePageType.ENTITY -> {
                // 安全要求：禁止实体页携带 NBT（剥离 { ... }）
                val rawEntity = cleanField("entity").substringBefore('{').trim()
                if (rawEntity.isEmpty() || ResourceLocation.tryParse(rawEntity) == null) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_entity", listOf(pageNum, rawEntity))
                }
                val scale = (page.field("scale").toFloatOrNull() ?: 1.0f).coerceIn(0.1f, 4.0f)
                val offset = (page.field("offset").toFloatOrNull() ?: 0.0f).coerceIn(-2.0f, 2.0f)
                val defaultRot = (page.field("default_rotation").toFloatOrNull() ?: -45.0f).coerceIn(-360f, 360f)
                val fields = buildMap {
                    put("entity", rawEntity)
                    put("scale", scale.toString())
                    put("offset", offset.toString())
                    put("rotate", page.boolField("rotate", true).toString())
                    put("default_rotation", defaultRot.toString())
                    cleanField("name", MAX_TITLE_LENGTH).takeIf { it.isNotEmpty() }?.let { put("name", it) }
                }
                PageValidation.Ok(NotePage(type = NotePageType.ENTITY, title = title, text = text, fields = fields))
            }
            NotePageType.LINK -> {
                if (!limits.allowExternalLinks) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.links_disabled", listOf(pageNum))
                }
                val url = cleanField("url", 512)
                if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
                    return PageValidation.Err("abadoned_greatwork.notes.error.bad_url", listOf(pageNum))
                }
                val linkText = cleanField("link_text", 64).ifEmpty { "Open Link" }
                PageValidation.Ok(
                    NotePage(
                        type = NotePageType.LINK,
                        title = title,
                        text = text,
                        fields = mapOf("url" to url, "link_text" to linkText),
                    )
                )
            }
            NotePageType.EMPTY -> PageValidation.Ok(
                NotePage(
                    type = NotePageType.EMPTY,
                    fields = mapOf("draw_filler" to page.boolField("draw_filler", true).toString()),
                )
            )
            NotePageType.IOTA_DISPLAY -> {
                val iotas = page.iotas.take(MAX_IOTAS_PER_PAGE).map { it.copy() }
                PageValidation.Ok(
                    NotePage(
                        type = NotePageType.IOTA_DISPLAY,
                        title = title,
                        text = text,
                        iotas = iotas,
                    )
                )
            }
        }
    }

    fun estimateNbtBytes(tag: CompoundTag): Int {
        val baos = ByteArrayOutputStream(512)
        runCatching {
            NbtIo.write(tag, DataOutputStream(baos))
        }.getOrElse { return Int.MAX_VALUE }
        return baos.size()
    }
}
