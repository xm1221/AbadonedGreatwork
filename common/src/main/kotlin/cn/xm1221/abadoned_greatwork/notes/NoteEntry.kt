package cn.xm1221.abadoned_greatwork.notes

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.nbt.TagParser
import java.util.UUID

/**
 * 玩家笔记支持的书页类型白名单。
 */
enum class NotePageType(
    val typeId: String,
    val langKey: String,
    val shortLabel: String,
) {
    TEXT("patchouli:text", "abadoned_greatwork.notes.page_type.text", "T"),
    SPOTLIGHT("patchouli:spotlight", "abadoned_greatwork.notes.page_type.spotlight", "S"),
    CRAFTING("patchouli:crafting", "abadoned_greatwork.notes.page_type.crafting", "C"),
    SMELTING("patchouli:smelting", "abadoned_greatwork.notes.page_type.smelting", "F"),
    HEX_PATTERN("hexcasting:pattern", "abadoned_greatwork.notes.page_type.pattern", "P"),
    HEX_MANUAL_PATTERN("hexcasting:manual_pattern", "abadoned_greatwork.notes.page_type.manual_pattern", "M"),
    IMAGE("patchouli:image", "abadoned_greatwork.notes.page_type.image", "I"),
    ENTITY("patchouli:entity", "abadoned_greatwork.notes.page_type.entity", "E"),
    LINK("patchouli:link", "abadoned_greatwork.notes.page_type.link", "L"),
    EMPTY("patchouli:empty", "abadoned_greatwork.notes.page_type.empty", "_"),
    IOTA_DISPLAY("abadoned_greatwork:iota_display", "abadoned_greatwork.notes.page_type.iota_display", "Ω");

    companion object {
        fun fromTypeId(id: String): NotePageType? = entries.firstOrNull { it.typeId == id }
    }
}

/**
 * 手工图案定义（用于 `hexcasting:manual_pattern`）。
 */
data class ManualPatternDef(
    val startDir: String = "EAST",
    val signature: String = "",
) {
    fun toTag(): CompoundTag = CompoundTag().apply {
        putString("startdir", startDir)
        putString("signature", signature)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("startdir", startDir)
        addProperty("signature", signature)
    }

    companion object {
        fun fromTag(tag: CompoundTag): ManualPatternDef = ManualPatternDef(
            startDir = tag.getString("startdir").ifBlank { "EAST" },
            signature = tag.getString("signature"),
        )

        fun fromJson(obj: JsonObject): ManualPatternDef = ManualPatternDef(
            startDir = obj.get("startdir")?.asString?.ifBlank { "EAST" } ?: "EAST",
            signature = obj.get("signature")?.asString.orEmpty(),
        )
    }
}

/**
 * 玩家笔记中的单页数据（NBT 与 JSON 同构）。
 */
data class NotePage(
    val type: NotePageType = NotePageType.TEXT,
    val title: String = "",
    val text: String = "",
    val fields: Map<String, String> = emptyMap(),
    val patterns: List<ManualPatternDef> = emptyList(),
    val images: List<String> = emptyList(),
    val iotas: List<CompoundTag> = emptyList(),
) {
    fun field(key: String, default: String = ""): String = fields[key] ?: default

    fun boolField(key: String, default: Boolean = false): Boolean =
        fields[key]?.toBooleanStrictOrNull() ?: default

    fun withField(key: String, value: String): NotePage {
        val next = fields.toMutableMap()
        if (value.isEmpty()) next.remove(key) else next[key] = value
        return copy(fields = next)
    }

    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("type", type.typeId)
        if (title.isNotEmpty()) tag.putString("title", title)
        if (text.isNotEmpty()) tag.putString("text", text)
        if (fields.isNotEmpty()) {
            val fTag = CompoundTag()
            for ((k, v) in fields) {
                fTag.putString(k, v)
            }
            tag.put("fields", fTag)
        }
        if (patterns.isNotEmpty()) {
            val pList = ListTag()
            patterns.forEach { pList.add(it.toTag()) }
            tag.put("patterns", pList)
        }
        if (images.isNotEmpty()) {
            val iList = ListTag()
            images.forEach { iList.add(StringTag.valueOf(it)) }
            tag.put("images", iList)
        }
        if (iotas.isNotEmpty()) {
            val ioList = ListTag()
            iotas.forEach { ioList.add(it.copy()) }
            tag.put("iotas", ioList)
        }
        return tag
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", type.typeId)
        if (title.isNotEmpty()) addProperty("title", title)
        if (text.isNotEmpty()) addProperty("text", text)
        for ((k, v) in fields) {
            if (v.isNotEmpty()) addProperty(k, v)
        }
        if (patterns.isNotEmpty()) {
            val arr = JsonArray()
            patterns.forEach { arr.add(it.toJson()) }
            add("patterns", arr)
        }
        if (images.isNotEmpty()) {
            val arr = JsonArray()
            images.forEach { arr.add(it) }
            add("images", arr)
        }
        if (iotas.isNotEmpty()) {
            val arr = JsonArray()
            iotas.forEach { arr.add(it.toString()) }
            add("iotas", arr)
        }
    }

    /**
     * 转为 Patchouli `BookEntry` 反序列化可用的页面 JSON。
     * 所有文本已预先调用过百分比转义，换行转换为 `$(br)`。
     */
    fun toPatchouliPageJson(sanitizedText: String, sanitizedTitle: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", type.typeId)
        val body = sanitizedText.replace("\r\n", "\n").replace("\n", "$(br)")
        when (type) {
            NotePageType.TEXT -> {
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("title", sanitizedTitle)
                obj.addProperty("text", body)
            }
            NotePageType.SPOTLIGHT -> {
                obj.addProperty("item", field("item", "minecraft:paper"))
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("title", sanitizedTitle)
                obj.addProperty("link_recipe", boolField("link_recipe", false))
                obj.addProperty("text", body)
            }
            NotePageType.CRAFTING, NotePageType.SMELTING -> {
                obj.addProperty("recipe", field("recipe"))
                val r2 = field("recipe2")
                if (r2.isNotEmpty()) obj.addProperty("recipe2", r2)
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("title", sanitizedTitle)
                obj.addProperty("text", body)
            }
            NotePageType.HEX_PATTERN -> {
                obj.addProperty("op_id", field("op_id"))
                // PatternProcessor 检查 vars.has("header")；仅在非空时写入 header
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("header", sanitizedTitle)
                val input = field("input")
                val output = field("output")
                if (input.isNotEmpty()) obj.addProperty("input", escapePercent(input))
                if (output.isNotEmpty()) obj.addProperty("output", escapePercent(output))
                obj.addProperty("text", body)
            }
            NotePageType.HEX_MANUAL_PATTERN -> {
                obj.addProperty("header", sanitizedTitle)
                val input = field("input")
                val output = field("output")
                if (input.isNotEmpty()) obj.addProperty("input", escapePercent(input))
                if (output.isNotEmpty()) obj.addProperty("output", escapePercent(output))
                val arr = JsonArray()
                for (p in patterns) {
                    arr.add(p.toJson())
                }
                if (arr.size() == 0) {
                    arr.add(ManualPatternDef("EAST", "").toJson())
                }
                obj.add("patterns", arr)
                obj.addProperty("stroke_order", boolField("stroke_order", true))
                obj.addProperty("text", body)
            }
            NotePageType.IMAGE -> {
                val arr = JsonArray()
                images.filter { it.isNotBlank() }.forEach { arr.add(it.trim()) }
                obj.add("images", arr)
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("title", sanitizedTitle)
                obj.addProperty("border", boolField("border", true))
                obj.addProperty("text", body)
            }
            NotePageType.ENTITY -> {
                obj.addProperty("entity", field("entity", "minecraft:allay"))
                val scale = field("scale").toFloatOrNull() ?: 1.0f
                val offset = field("offset").toFloatOrNull() ?: 0.0f
                val defaultRot = field("default_rotation").toFloatOrNull() ?: -45.0f
                obj.addProperty("scale", scale)
                obj.addProperty("offset", offset)
                obj.addProperty("rotate", boolField("rotate", true))
                obj.addProperty("default_rotation", defaultRot)
                val name = field("name").ifEmpty { sanitizedTitle }
                if (name.isNotEmpty()) obj.addProperty("name", escapePercent(name))
                obj.addProperty("text", body)
            }
            NotePageType.LINK -> {
                val url = field("url")
                val linkText = field("link_text").ifBlank { url.ifBlank { "Link" } }
                obj.addProperty("url", url)
                obj.addProperty("link_text", escapePercent(linkText))
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("title", sanitizedTitle)
                obj.addProperty("text", body)
            }
            NotePageType.EMPTY -> {
                obj.addProperty("draw_filler", boolField("draw_filler", true))
            }
            NotePageType.IOTA_DISPLAY -> {
                if (sanitizedTitle.isNotEmpty()) obj.addProperty("title", sanitizedTitle)
                val arr = JsonArray()
                iotas.forEach { arr.add(it.toString()) }
                obj.add("iotas", arr)
                obj.addProperty("text", body)
            }
        }
        return obj
    }

    companion object {
        private val RESERVED_JSON_KEYS = setOf("type", "title", "text", "patterns", "images", "iotas")

        fun escapePercent(raw: String): String = raw.replace("%", "%%")

        fun fromTag(tag: CompoundTag): NotePage? {
            val type = NotePageType.fromTypeId(tag.getString("type")) ?: return null
            val title = tag.getString("title")
            val text = tag.getString("text")
            val fields = mutableMapOf<String, String>()
            if (tag.contains("fields", Tag.TAG_COMPOUND.toInt())) {
                val fTag = tag.getCompound("fields")
                for (k in fTag.allKeys) {
                    fields[k] = fTag.getString(k)
                }
            }
            val patterns = mutableListOf<ManualPatternDef>()
            if (tag.contains("patterns", Tag.TAG_LIST.toInt())) {
                val pList = tag.getList("patterns", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until pList.size) {
                    patterns.add(ManualPatternDef.fromTag(pList.getCompound(i)))
                }
            }
            val images = mutableListOf<String>()
            if (tag.contains("images", Tag.TAG_LIST.toInt())) {
                val iList = tag.getList("images", Tag.TAG_STRING.toInt())
                for (i in 0 until iList.size) {
                    images.add(iList.getString(i))
                }
            }
            val iotas = mutableListOf<CompoundTag>()
            if (tag.contains("iotas", Tag.TAG_LIST.toInt())) {
                val ioList = tag.getList("iotas", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until ioList.size) {
                    iotas.add(ioList.getCompound(i).copy())
                }
            }
            return NotePage(type, title, text, fields, patterns, images, iotas)
        }

        fun fromJson(obj: JsonObject): NotePage? {
            val type = NotePageType.fromTypeId(obj.get("type")?.asString.orEmpty()) ?: return null
            val title = obj.get("title")?.asString.orEmpty()
            val text = obj.get("text")?.asString.orEmpty()
            val fields = mutableMapOf<String, String>()
            for ((k, v) in obj.entrySet()) {
                if (k !in RESERVED_JSON_KEYS && v.isJsonPrimitive) {
                    fields[k] = v.asString
                }
            }
            val patterns = mutableListOf<ManualPatternDef>()
            obj.getAsJsonArray("patterns")?.forEach { el ->
                if (el.isJsonObject) patterns.add(ManualPatternDef.fromJson(el.asJsonObject))
            }
            val images = mutableListOf<String>()
            obj.getAsJsonArray("images")?.forEach { el ->
                if (el.isJsonPrimitive) images.add(el.asString)
            }
            val iotas = mutableListOf<CompoundTag>()
            obj.getAsJsonArray("iotas")?.forEach { el ->
                parseSnbtCompound(el)?.let { iotas.add(it) }
            }
            return NotePage(type, title, text, fields, patterns, images, iotas)
        }

        private fun parseSnbtCompound(el: JsonElement): CompoundTag? = runCatching {
            val raw = if (el.isJsonPrimitive) el.asString else el.toString()
            TagParser.parseTag(raw)
        }.getOrNull()
    }
}

/**
 * 一条完整的玩家手册条目。
 */
data class NoteEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val author: String = "",
    val time: Long = System.currentTimeMillis(),
    val icon: String = DEFAULT_ICON,
    val color: String = DEFAULT_COLOR,
    val pages: List<NotePage> = listOf(NotePage()),
) {
    fun toTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString(KEY_ID, id)
        tag.putString(KEY_TITLE, title)
        tag.putString(KEY_AUTHOR, author)
        tag.putLong(KEY_TIME, time)
        tag.putString(KEY_ICON, icon)
        tag.putString(KEY_COLOR, color)
        val pList = ListTag()
        pages.forEach { pList.add(it.toTag()) }
        tag.put(KEY_PAGES, pList)
        return tag
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(KEY_ID, id)
        addProperty(KEY_TITLE, title)
        addProperty(KEY_AUTHOR, author)
        addProperty(KEY_TIME, time)
        addProperty(KEY_ICON, icon)
        addProperty(KEY_COLOR, color)
        val arr = JsonArray()
        pages.forEach { arr.add(it.toJson()) }
        add(KEY_PAGES, arr)
    }

    /**
     * 转为 Patchouli `BookEntry` 构建所需的 JSON。
     */
    fun toPatchouliEntryJson(sortIndex: Int = 0): JsonObject {
        val obj = JsonObject()
        val displayTitle = title.ifBlank { "Untitled Note" }
        obj.addProperty("name", NotePage.escapePercent(displayTitle))
        obj.addProperty("category", PLAYER_NOTES_CATEGORY)
        obj.addProperty("icon", icon.ifBlank { DEFAULT_ICON })
        obj.addProperty("entry_color", color.ifBlank { DEFAULT_COLOR })
        obj.addProperty("sortnum", 100 + sortIndex)
        obj.addProperty("read_by_default", true)

        val arr = JsonArray()
        for (page in pages) {
            arr.add(
                page.toPatchouliPageJson(
                    sanitizedText = NotePage.escapePercent(page.text),
                    sanitizedTitle = NotePage.escapePercent(page.title),
                )
            )
        }
        if (arr.size() == 0) {
            arr.add(NotePage(NotePageType.EMPTY).toPatchouliPageJson("", ""))
        }
        obj.add("pages", arr)
        return obj
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_TIME = "time"
        const val KEY_ICON = "icon"
        const val KEY_COLOR = "color"
        const val KEY_PAGES = "pages"

        const val DEFAULT_ICON = "abadoned_greatwork:amethyst_pen"
        const val DEFAULT_COLOR = "54398a"
        const val PLAYER_NOTES_CATEGORY = "hexcasting:abadoned_greatwork/player_notes"
        const val ENTRY_PATH_PREFIX = "user_notes/"

        fun entryResourcePath(id: String): String {
            val safe = id.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
            return "$ENTRY_PATH_PREFIX$safe"
        }

        fun fromTag(tag: CompoundTag): NoteEntry? {
            // 兼容旧版 HexGuide 单页笔记格式 {title, body, author, id, time}
            if (!tag.contains(KEY_PAGES, Tag.TAG_LIST.toInt()) && tag.contains("body", Tag.TAG_STRING.toInt())) {
                val id = tag.getString(KEY_ID).ifBlank { UUID.randomUUID().toString() }
                return NoteEntry(
                    id = id,
                    title = tag.getString(KEY_TITLE),
                    author = tag.getString(KEY_AUTHOR),
                    time = tag.getLong(KEY_TIME),
                    pages = listOf(NotePage(type = NotePageType.TEXT, text = tag.getString("body"))),
                )
            }
            val id = tag.getString(KEY_ID).ifBlank { UUID.randomUUID().toString() }
            val title = tag.getString(KEY_TITLE)
            val author = tag.getString(KEY_AUTHOR)
            val time = tag.getLong(KEY_TIME)
            val icon = tag.getString(KEY_ICON).ifBlank { DEFAULT_ICON }
            val color = tag.getString(KEY_COLOR).ifBlank { DEFAULT_COLOR }
            val pages = mutableListOf<NotePage>()
            val pList = tag.getList(KEY_PAGES, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until pList.size) {
                NotePage.fromTag(pList.getCompound(i))?.let { pages.add(it) }
            }
            return NoteEntry(id, title, author, time, icon, color, pages)
        }

        fun fromJson(obj: JsonObject): NoteEntry? {
            val pagesEl = obj.get("pages")
            if (pagesEl == null || !pagesEl.isJsonArray) return null
            val pages = mutableListOf<NotePage>()
            for (el in pagesEl.asJsonArray) {
                if (el.isJsonObject) {
                    val p = NotePage.fromJson(el.asJsonObject) ?: return null
                    pages.add(p)
                }
            }
            return NoteEntry(
                id = obj.get(KEY_ID)?.asString?.ifBlank { null } ?: UUID.randomUUID().toString(),
                title = obj.get(KEY_TITLE)?.asString.orEmpty(),
                author = obj.get(KEY_AUTHOR)?.asString.orEmpty(),
                time = obj.get(KEY_TIME)?.asLong ?: System.currentTimeMillis(),
                icon = obj.get(KEY_ICON)?.asString?.ifBlank { null } ?: DEFAULT_ICON,
                color = obj.get(KEY_COLOR)?.asString?.ifBlank { null } ?: DEFAULT_COLOR,
                pages = pages,
            )
        }

        fun parseJsonString(raw: String): NoteEntry? = runCatching {
            val el = JsonParser.parseString(raw)
            if (!el.isJsonObject) return@runCatching null
            val root = el.asJsonObject
            val entryObj = if (root.has("entry") && root.get("entry").isJsonObject) {
                root.getAsJsonObject("entry")
            } else {
                root
            }
            fromJson(entryObj)
        }.getOrNull()
    }
}
