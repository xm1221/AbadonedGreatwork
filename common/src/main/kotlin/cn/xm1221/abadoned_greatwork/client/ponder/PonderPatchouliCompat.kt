package cn.xm1221.abadoned_greatwork.client.ponder

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.common.lib.hex.HexActions
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.architectury.platform.Platform
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.resources.ResourceLocation
import vazkii.patchouli.client.book.ClientBookRegistry
import vazkii.patchouli.common.book.BookRegistry
import java.util.Locale

/**
 * 可选《帕秋莉手册》（Patchouli）《咒术笔记》检索与直达兼容层。
 *
 * 严格遵循可选依赖隔离契约：外部只应调用本对象，
 * 所有 `vazkii.patchouli.*` 类引用全部隔离在 [PonderPatchouliBridge] 内并仅于 `Platform.isModLoaded("patchouli")` 时加载。
 */
object PonderPatchouliCompat {

    data class ManualHit(
        val entryId: ResourceLocation,
        val pageIndex: Int,
        val entryName: String,
        val snippet: String,
        val opId: String?,
        val patternSigs: List<String>,
        val searchText: String,
    )

    fun isAvailable(): Boolean = Platform.isModLoaded("patchouli")

    fun invalidateCache() {
        if (isAvailable()) {
            PonderPatchouliBridge.invalidate()
        }
    }

    fun searchManual(query: String): List<ManualHit> {
        if (!isAvailable()) return emptyList()
        return PonderPatchouliBridge.searchText(query)
    }

    fun searchByPattern(sig: String, actionId: ResourceLocation?): List<ManualHit> {
        if (!isAvailable()) return emptyList()
        return PonderPatchouliBridge.searchByPattern(sig, actionId)
    }

    fun openManualPage(entryId: ResourceLocation, pageIndex: Int) {
        if (!isAvailable()) return
        PonderPatchouliBridge.openPage(entryId, pageIndex)
    }
}

private object PonderPatchouliBridge {

    private val PATCHOULI_TAG_REGEX = Regex("\\$\\([^)]*\\)")
    private var cachedHits: List<PonderPatchouliCompat.ManualHit>? = null
    private var cachedLang: String = ""

    fun invalidate() {
        cachedHits = null
        cachedLang = ""
    }

    private fun ensureIndex(): List<PonderPatchouliCompat.ManualHit> {
        val currentLang = runCatching { Minecraft.getInstance().languageManager.selected }.getOrDefault("")
        val existing = cachedHits
        if (existing != null && cachedLang == currentLang) {
            return existing
        }

        val out = ArrayList<PonderPatchouliCompat.ManualHit>()
        val book = BookRegistry.INSTANCE.books[HexAPI.modLoc("thehexbook")] ?: return emptyList()
        for (cat in book.contents.categories.values) {
            for (entry in cat.entries) {
                if (entry.isLocked) continue
                val entryName = entry.name.string
                val entryId = entry.id
                val pages = entry.pages
                for (pi in pages.indices) {
                    val root = pages[pi].sourceObject ?: continue
                    val sb = StringBuilder(entryName).append(' ')
                    var pageOpId: String? = null
                    val sigs = LinkedHashSet<String>()

                    val ty = root.strOrEmpty("type")
                    if (root.has("op_id") && root.get("op_id").isJsonPrimitive) {
                        val op = root.get("op_id").asString
                        pageOpId = op
                        sb.append(op).append(' ')
                        val actName = resolveText("hexcasting.action.$op")
                        if (actName != "hexcasting.action.$op") {
                            sb.append(actName).append(' ')
                        }
                        val bookActName = resolveText("hexcasting.action.book.$op")
                        if (bookActName != "hexcasting.action.book.$op") {
                            sb.append(bookActName).append(' ')
                        }
                        ResourceLocation.tryParse(op)?.let { rl ->
                            runCatching { HexActions.REGISTRY.get(rl)?.prototype()?.anglesSignature() }
                                .getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    sigs.add(it)
                                    sb.append(it).append(' ')
                                }
                        }
                    }

                    if (root.has("patterns") && root.get("patterns").isJsonArray) {
                        for (el in root.getAsJsonArray("patterns")) {
                            if (el.isJsonObject) {
                                val obj = el.asJsonObject
                                if (obj.has("signature") && obj.get("signature").isJsonPrimitive) {
                                    val sig = obj.get("signature").asString
                                    if (sig.isNotEmpty()) {
                                        sigs.add(sig)
                                        sb.append(sig).append(' ')
                                    }
                                }
                            }
                        }
                    }

                    val headerTxt = extractJsonText(root.get("header"))
                    val titleTxt = extractJsonText(root.get("title"))
                    val bodyTxt = extractJsonText(root.get("text"))
                    val inTxt = if (ty == "hexcasting:pattern") extractJsonText(root.get("input")) else ""
                    val outTxt = if (ty == "hexcasting:pattern") extractJsonText(root.get("output")) else ""

                    if (headerTxt.isNotEmpty()) sb.append(headerTxt).append(' ')
                    if (titleTxt.isNotEmpty()) sb.append(titleTxt).append(' ')
                    if (inTxt.isNotEmpty()) sb.append(inTxt).append(' ')
                    if (outTxt.isNotEmpty()) sb.append(outTxt).append(' ')
                    if (bodyTxt.isNotEmpty()) sb.append(bodyTxt).append(' ')

                    val rawPreview = when {
                        bodyTxt.isNotEmpty() -> bodyTxt
                        headerTxt.isNotEmpty() -> headerTxt
                        titleTxt.isNotEmpty() -> titleTxt
                        pageOpId != null -> resolveText("hexcasting.action.$pageOpId")
                        else -> ""
                    }
                    val snippet = cleanPatchouliMarkup(rawPreview)
                    out.add(
                        PonderPatchouliCompat.ManualHit(
                            entryId = entryId,
                            pageIndex = pi,
                            entryName = entryName,
                            snippet = snippet,
                            opId = pageOpId,
                            patternSigs = sigs.toList(),
                            searchText = cleanPatchouliMarkup(sb.toString()).lowercase(Locale.ROOT),
                        )
                    )
                }
            }
        }
        cachedHits = out
        cachedLang = currentLang
        return out
    }

    fun searchText(query: String): List<PonderPatchouliCompat.ManualHit> {
        val all = ensureIndex()
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return all
        return all.filter { it.searchText.contains(q) }
    }

    fun searchByPattern(sig: String, actionId: ResourceLocation?): List<PonderPatchouliCompat.ManualHit> {
        val all = ensureIndex()
        val actStr = actionId?.toString()
        val actName = if (actionId != null) {
            resolveText("hexcasting.action.$actionId").lowercase(Locale.ROOT)
        } else {
            null
        }
        val bookActName = if (actionId != null) {
            resolveText("hexcasting.action.book.$actionId").lowercase(Locale.ROOT)
        } else {
            null
        }

        val ordered = LinkedHashSet<PonderPatchouliCompat.ManualHit>()
        // 1) op_id 精确命中
        if (actStr != null) {
            all.filterTo(ordered) { it.opId == actStr }
        }
        // 2) 图案角度签名精确命中
        if (sig.isNotEmpty()) {
            all.filterTo(ordered) { it.patternSigs.contains(sig) }
        }
        // 3) 动作本地化名称引用命中（如普通说明页引用该法术名称）
        if (actName != null && !actName.startsWith("hexcasting.action.")) {
            all.filterTo(ordered) { it.searchText.contains(actName) }
        }
        if (bookActName != null && !bookActName.startsWith("hexcasting.action.book.")) {
            all.filterTo(ordered) { it.searchText.contains(bookActName) }
        }
        return ordered.toList()
    }

    fun openPage(entryId: ResourceLocation, pageIndex: Int) {
        // 实测 Patchouli BookContents.setTopEntry(entry, page) 内部会将 page 除以 2 转为 spread，因此直接传入 pageIndex
        ClientBookRegistry.INSTANCE.displayBookGui(HexAPI.modLoc("thehexbook"), entryId, pageIndex)
    }

    private fun JsonObject.strOrEmpty(key: String): String =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else ""

    private fun resolveText(key: String): String =
        if (I18n.exists(key)) I18n.get(key) else key

    private fun extractJsonText(el: JsonElement?): String {
        if (el == null || el.isJsonNull) return ""
        if (el.isJsonPrimitive) {
            val p = el.asJsonPrimitive
            return if (p.isString) resolveText(p.asString) else p.toString()
        }
        if (el.isJsonObject) {
            val o = el.asJsonObject
            if (o.has("translate") && o.get("translate").isJsonPrimitive) {
                return resolveText(o.get("translate").asString)
            }
            if (o.has("text") && o.get("text").isJsonPrimitive) {
                return o.get("text").asString
            }
            return o.toString()
        }
        return el.toString()
    }

    private fun cleanPatchouliMarkup(raw: String): String =
        raw.replace(PATCHOULI_TAG_REGEX, " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
}
