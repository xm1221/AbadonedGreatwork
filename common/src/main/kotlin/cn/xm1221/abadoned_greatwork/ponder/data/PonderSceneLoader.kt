package cn.xm1221.abadoned_greatwork.ponder.data

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderIndexSyncS2C
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.architectury.platform.Platform
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManager

/**
 * 全数据驱动的思索资源加载器（支持 `/reload` 热更新）。
 *
 * 读取路径：
 * - `data/<ns>/hex_ponder/categories/<id>.json`：目录分类
 * - `data/<ns>/hex_ponder/scenes/<id>.json`：思索场景（含动画、气泡、知识点、触发器）
 * - `data/<ns>/spellplays/<id>.json`：向下兼容 HexGuide 旧版演示配置
 * - `data/<ns>/pattern_vector/<id>.json`：全局图案默认网格起点 `[q, r]`
 * - `data/<ns>/hex_ponder/iotas/<id>.json` 与 `data/<ns>/iotas/<id>.json`：预设 Iota 资源
 */
object PonderSceneLoader {

    const val DEFAULT_CURSOR_ITEM = "hexcasting:staff/oak"
    private const val DEFAULT_COLOR_ESCAPED = -1

    @Volatile
    var latestServer: MinecraftServer? = null

    @Volatile
    var categories: List<PonderCategory> = emptyList()
        private set

    @Volatile
    var scenes: Map<ResourceLocation, PonderSceneDef> = emptyMap()
        private set

    @Volatile
    var presetIotas: Map<ResourceLocation, String> = emptyMap()
        private set

    @Volatile
    var patternVectors: Map<String, Pair<Int, Int>> = emptyMap()
        private set

    @Volatile
    var indexPayload: PonderIndexPayload = PonderIndexPayload(emptyList(), emptyList())
        private set

    fun reload(rm: ResourceManager) {
        val loadedIotas = loadIotaPresets(rm)
        val loadedVectors = loadPatternVectors(rm)
        val loadedCats = loadCategories(rm)
        val loadedScenes = loadScenes(rm, loadedVectors)

        presetIotas = loadedIotas
        patternVectors = loadedVectors
        categories = loadedCats
        scenes = loadedScenes

        val entries = loadedScenes.values
            .sortedWith(compareBy<PonderSceneDef>({ it.sortOrder }, { it.id.toString() }))
            .map { it.toIndexEntry() }
        val payload = PonderIndexPayload(loadedCats, entries)
        indexPayload = payload

        Abadoned_greatwork.LOGGER.info(
            "[HexPonder] 已重载思索资源：{} 个分类，{} 个场景，{} 条图案起点，{} 个预设 Iota",
            loadedCats.size,
            loadedScenes.size,
            loadedVectors.size,
            loadedIotas.size,
        )

        latestServer?.playerList?.players?.forEach { player ->
            MsgPonderIndexSyncS2C.sendChunked(player, payload)
        }
    }

    fun syncIndexTo(player: ServerPlayer) {
        MsgPonderIndexSyncS2C.sendChunked(player, indexPayload)
    }

    fun getScene(id: ResourceLocation): PonderSceneDef? = scenes[id]

    private fun loadIotaPresets(rm: ResourceManager): Map<ResourceLocation, String> {
        val out = LinkedHashMap<ResourceLocation, String>()
        for (folder in listOf("iotas", "hex_ponder/iotas")) {
            val resources = rm.listResources(folder) { it.path.endsWith(".json") }
            for ((rl, res) in resources) {
                val cleanPath = rl.path.removePrefix("$folder/")
                val key = ResourceLocation(rl.namespace, cleanPath)
                try {
                    res.open().use { stream ->
                        out[key] = stream.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    Abadoned_greatwork.LOGGER.warn("[HexPonder] 读取预设 iota 失败: {}", rl, e)
                }
            }
        }
        return out
    }

    private fun loadPatternVectors(rm: ResourceManager): Map<String, Pair<Int, Int>> {
        val merged = LinkedHashMap<String, Pair<Int, Int>>()
        val resources = rm.listResources("pattern_vector") { it.path.endsWith(".json") }
        val sortedKeys = resources.keys.sortedBy { it.path }
        for (rl in sortedKeys) {
            val res = resources[rl] ?: continue
            try {
                res.open().use { stream ->
                    val root = JsonParser.parseString(stream.readBytes().toString(Charsets.UTF_8)).asJsonObject
                    for ((actionId, elem) in root.entrySet()) {
                        if (elem.isJsonObject && elem.asJsonObject.has("origin")) {
                            val arr = elem.asJsonObject.getAsJsonArray("origin")
                            if (arr.size() >= 2) {
                                merged[actionId] = arr[0].asInt to arr[1].asInt
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Abadoned_greatwork.LOGGER.warn("[HexPonder] 解析 pattern_vector 失败: {}", rl, e)
            }
        }
        return merged
    }

    private fun loadCategories(rm: ResourceManager): List<PonderCategory> {
        val map = LinkedHashMap<String, PonderCategory>()
        val resources = rm.listResources("hex_ponder/categories") { it.path.endsWith(".json") }
        for ((rl, res) in resources) {
            try {
                res.open().use { stream ->
                    val obj = JsonParser.parseString(stream.readBytes().toString(Charsets.UTF_8)).asJsonObject
                    val defaultId = rl.path.removePrefix("hex_ponder/categories/").removeSuffix(".json")
                    val id = obj.str("id", defaultId)
                    map[id] = PonderCategory(
                        id = id,
                        title = obj.str("title", id),
                        description = obj.str("description", ""),
                        iconItem = obj.str("icon", "hexcasting:staff/oak"),
                        sortOrder = obj.int("sort_order", 100),
                    )
                }
            } catch (e: Exception) {
                Abadoned_greatwork.LOGGER.warn("[HexPonder] 解析分类失败: {}", rl, e)
            }
        }
        if (map.isEmpty()) {
            map["basics"] = PonderCategory("basics", "hexponder.category.basics", "", "hexcasting:staff/oak", 0)
            map["control_flow"] = PonderCategory("control_flow", "hexponder.category.control_flow", "", "minecraft:ender_eye", 1)
            map["spells"] = PonderCategory("spells", "hexponder.category.spells", "", "hexcasting:focus", 2)
            map["greatwork"] = PonderCategory("greatwork", "hexponder.category.greatwork", "", "abadoned_greatwork:item_truth_crystal_0", 3)
        }
        return map.values.sortedWith(compareBy<PonderCategory>({ it.sortOrder }, { it.id }))
    }

    private fun loadScenes(
        rm: ResourceManager,
        vectors: Map<String, Pair<Int, Int>>,
    ): Map<ResourceLocation, PonderSceneDef> {
        val out = LinkedHashMap<ResourceLocation, PonderSceneDef>()

        // 1) 新格式：data/<ns>/hex_ponder/scenes/*.json
        val modern = rm.listResources("hex_ponder/scenes") { it.path.endsWith(".json") }
        for ((rl, res) in modern.entries.sortedBy { it.key.toString() }) {
            try {
                val scene = res.open().use { stream ->
                    val raw = stream.readBytes().toString(Charsets.UTF_8)
                    val fallbackId = ResourceLocation(
                        rl.namespace,
                        rl.path.removePrefix("hex_ponder/scenes/").removeSuffix(".json"),
                    )
                    parseScene(fallbackId, raw, vectors)
                }
                if (scene != null && (scene.requiresMod.isEmpty() || Platform.isModLoaded(scene.requiresMod))) {
                    out[scene.id] = scene
                }
            } catch (e: Exception) {
                Abadoned_greatwork.LOGGER.warn("[HexPonder] 解析思索场景失败: {}", rl, e)
            }
        }

        // 2) 旧版 HexGuide spellplays 兼容：data/<ns>/spellplays/*.json
        val legacy = rm.listResources("spellplays") { it.path.endsWith(".json") }
        for ((rl, res) in legacy.entries.sortedBy { it.key.toString() }) {
            try {
                val scene = res.open().use { stream ->
                    val raw = stream.readBytes().toString(Charsets.UTF_8)
                    val fallbackId = ResourceLocation(
                        rl.namespace,
                        rl.path.removePrefix("spellplays/").removeSuffix(".json"),
                    )
                    parseScene(fallbackId, raw, vectors)
                }
                if (scene != null && !out.containsKey(scene.id) &&
                    (scene.requiresMod.isEmpty() || Platform.isModLoaded(scene.requiresMod))
                ) {
                    out[scene.id] = scene
                }
            } catch (e: Exception) {
                Abadoned_greatwork.LOGGER.warn("[HexPonder] 解析兼容 spellplay 失败: {}", rl, e)
            }
        }

        return out
    }

    private fun parseScene(
        fallbackId: ResourceLocation,
        jsonStr: String,
        vectors: Map<String, Pair<Int, Int>>,
    ): PonderSceneDef? {
        val root = JsonParser.parseString(jsonStr).asJsonObject
        val id = if (root.has("id")) {
            ResourceLocation.tryParse(root.get("id").asString) ?: fallbackId
        } else {
            fallbackId
        }

        val title = root.str("title", id.path)
        val subtitle = root.str("subtitle", "")
        val category = root.str("category", "basics")
        val sortOrder = root.int("sort_order", 100)
        val iconItem = root.str("icon", "hexcasting:staff/oak")
        val cursorItem = if (root.has("cursor_item")) root.get("cursor_item").asString else DEFAULT_CURSOR_ITEM
        val requiresMod = root.str("requires_mod", "")
        val hexSize = if (root.has("hex_size")) root.get("hex_size").asFloat else 22f
        val defaultInterval = root.int("interval", 40)
        val defaultStrokeTicks = root.int("stroke_ticks", 14)
        val clearBefore = if (root.has("clear_before")) root.get("clear_before").asBoolean else true
        val startDir = root.str("start_dir", "NORTH_EAST")
        val escapedColor = if (root.has("escaped_color")) {
            parseColor(root.get("escaped_color").asString, DEFAULT_COLOR_ESCAPED)
        } else {
            DEFAULT_COLOR_ESCAPED
        }

        val triggers = parseTriggers(root.getAsJsonObject("triggers"))
        val knowledge = parseKnowledgeCards(root.getAsJsonArray("knowledge_cards"))
        val steps = parseSteps(
            arr = root.getAsJsonArray("steps") ?: JsonArray(),
            globalInterval = defaultInterval,
            globalStrokeTicks = defaultStrokeTicks,
            globalStartDir = startDir,
            vectors = vectors,
        )

        return PonderSceneDef(
            id = id,
            title = title,
            subtitle = subtitle,
            category = category,
            sortOrder = sortOrder,
            iconItem = iconItem,
            cursorItem = cursorItem,
            requiresMod = requiresMod,
            hexSize = hexSize,
            defaultInterval = defaultInterval,
            defaultStrokeTicks = defaultStrokeTicks,
            clearBefore = clearBefore,
            startDir = startDir,
            escapedColor = escapedColor,
            triggers = triggers,
            knowledgeCards = knowledge,
            steps = steps,
        )
    }

    private fun parseTriggers(obj: JsonObject?): PonderTriggers {
        if (obj == null) return PonderTriggers()
        return PonderTriggers(
            items = obj.strList("items"),
            blocks = obj.strList("blocks"),
            actions = obj.strList("actions"),
            riddles = obj.strList("riddles"),
        )
    }

    private fun parseKnowledgeCards(arr: JsonArray?): List<PonderKnowledgeCardDef> {
        if (arr == null) return emptyList()
        val out = ArrayList<PonderKnowledgeCardDef>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            out.add(
                PonderKnowledgeCardDef(
                    title = o.str("title", ""),
                    body = o.str("body", ""),
                    inlineIotas = o.strList("inline_iotas"),
                )
            )
        }
        return out
    }

    private fun parseSteps(
        arr: JsonArray,
        globalInterval: Int,
        globalStrokeTicks: Int,
        globalStartDir: String,
        vectors: Map<String, Pair<Int, Int>>,
    ): List<PonderStepDef> {
        val out = ArrayList<PonderStepDef>(arr.size())
        var lastColor = -1
        var lastTitle = ""
        var lastChapter = ""

        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val type = o.str("type", "execute")
            val action = o.str("action", "")
            val pattern = o.str("pattern", "")
            val stepStartDir = o.str("start_dir", globalStartDir)
            val holdTicks = when {
                o.has("hold_ticks") -> o.get("hold_ticks").asInt
                o.has("interval") -> o.get("interval").asInt
                else -> globalInterval
            }
            val strokeTicks = o.int("stroke_ticks", globalStrokeTicks)

            val stepColor = if (o.has("color")) {
                val cStr = o.get("color").asString
                if (cStr.isEmpty()) {
                    lastColor = -1
                    -1
                } else {
                    val parsed = parseColor(cStr, -1)
                    lastColor = parsed
                    parsed
                }
            } else {
                lastColor
            }

            val stepTitle = if (o.has("title")) {
                val tStr = o.get("title").asString
                lastTitle = tStr
                tStr
            } else {
                lastTitle
            }

            val stepChapter = if (o.has("chapter")) {
                val ch = o.get("chapter").asString
                lastChapter = ch
                ch
            } else {
                lastChapter
            }

            val cursorItem = if (o.has("cursor_item")) o.get("cursor_item").asString else null
            val clearOverride = if (o.has("clear_before")) o.get("clear_before").asBoolean else null

            val (oq, or) = when {
                o.has("origin") && o.get("origin").isJsonArray -> {
                    val oc = o.getAsJsonArray("origin")
                    if (oc.size() >= 2) oc[0].asInt to oc[1].asInt else (-1 to 2)
                }
                o.has("q") || o.has("r") -> {
                    o.int("q", -1) to o.int("r", 2)
                }
                action.isNotEmpty() -> {
                    vectors[action] ?: (-1 to 2)
                }
                else -> -1 to 2
            }

            val peekIndex = if (o.has("index")) o.get("index").asInt else -1
            val peekIndices = if (o.has("indices") && o.get("indices").isJsonArray) {
                o.getAsJsonArray("indices").map { it.asInt }
            } else {
                emptyList()
            }

            val callout = if (o.has("callout") && o.get("callout").isJsonObject) {
                val co = o.getAsJsonObject("callout")
                PonderCalloutDef(
                    anchor = co.str("anchor", "canvas"),
                    anchorIndex = co.int("anchor_index", 0),
                    anchorQ = if (co.has("anchor_q")) co.get("anchor_q").asInt else null,
                    anchorR = if (co.has("anchor_r")) co.get("anchor_r").asInt else null,
                    accentColor = if (co.has("color")) parseColor(co.get("color").asString, 0xFFA875FF.toInt()) else 0xFFA875FF.toInt(),
                    text = co.str("text", ""),
                    inlineIotas = co.strList("inline_iotas"),
                    pause = co.has("pause") && co.get("pause").asBoolean,
                )
            } else {
                null
            }

            out.add(
                PonderStepDef(
                    type = type,
                    action = action,
                    pattern = pattern,
                    startDir = stepStartDir,
                    originQ = oq,
                    originR = or,
                    color = stepColor,
                    cursorItem = cursorItem,
                    strokeTicks = strokeTicks.coerceAtLeast(1),
                    holdTicks = holdTicks.coerceAtLeast(1),
                    title = stepTitle,
                    chapter = stepChapter,
                    pushRaw = o.get("push"),
                    peekIndex = peekIndex,
                    peekIndices = peekIndices,
                    callout = callout,
                    clearBeforeOverride = clearOverride,
                )
            )
        }
        return out
    }

    private fun parseColor(raw: String, fallback: Int): Int {
        return try {
            val s = raw.trim()
            val rgb = when {
                s.startsWith("#") -> s.substring(1).toLong(16).toInt()
                s.startsWith("0x") || s.startsWith("0X") -> s.substring(2).toLong(16).toInt()
                else -> s.toInt()
            }
            0xFF000000.toInt() or (rgb and 0xFFFFFF)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun JsonObject.str(key: String, default: String): String =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else default

    private fun JsonObject.int(key: String, default: Int): Int =
        if (has(key) && get(key).isJsonPrimitive) get(key).asInt else default

    private fun JsonObject.strList(key: String): List<String> {
        val arr = getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
    }
}
