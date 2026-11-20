package cn.xm1221.abadoned_greatwork.ponder.data

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.api.iotaFromNbt
import cn.xm1221.abadoned_greatwork.api.iotaToNbt
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.architectury.platform.Platform
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.TagParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterOutputStream

/**
 * 纯服务端/通用 Iota 文本编解码器（迁移自 HexGuide 的 IotaInlineData 与 resolveIotaString，无 Inline 模组依赖）。
 *
 * 支持格式：
 * - `""` → null（不入栈）
 * - `"null"` → NullIota
 * - `"double:<数字>"` → DoubleIota
 * - `"vec:{x,y,z}"` → Vec3Iota
 * - `"pattern:<action_id>"` → 已注册图案 PatternIota
 * - `"pattern{<dir>,<angles>}"` / `"pattern[<dir>,<angles>]"` → 自定义 PatternIota
 * - `"iota:[<元素>,...]"` → 递归 ListIota（括号深度安全分割）
 * - `"iota:<ns>:<name>.json"` / `"iota:<name>.json"` → 从服务端数据包或游戏目录读取预设 Iota JSON
 * - `"iota:<a85>"` → Ascii85 + Deflate 压缩编码
 * - JSON 对象 `{"nbt": "<SNBT>"}` 或直接 NBT JSON
 */
object PonderIotaCodec {

    private const val A85_BASE = 33

    fun resolveElement(el: JsonElement?, presetIotas: Map<ResourceLocation, String>): CompoundTag? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    if (obj.has("nbt")) {
                        TagParser.parseTag(obj.get("nbt").asString)
                    } else {
                        TagParser.parseTag(obj.toString())
                    }
                }
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> {
                    resolveString(el.asString, presetIotas)
                }
                else -> null
            }
        } catch (e: Exception) {
            Abadoned_greatwork.LOGGER.warn("[HexPonder] 解析 push/inline iota 失败: {}", el, e)
            null
        }
    }

    fun resolveString(rawInput: String, presetIotas: Map<ResourceLocation, String>): CompoundTag? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        return try {
            when {
                trimmed == "null" -> iotaToNbt(NullIota())
                trimmed.startsWith("double:") -> {
                    val d = trimmed.substring(7).trim().toDouble()
                    iotaToNbt(DoubleIota(d))
                }
                trimmed.startsWith("vec:{") && trimmed.endsWith("}") -> {
                    val inner = trimmed.substring(5, trimmed.length - 1)
                    val parts = inner.split(",")
                    val x = parts[0].trim().toDouble()
                    val y = parts[1].trim().toDouble()
                    val z = parts[2].trim().toDouble()
                    iotaToNbt(Vec3Iota(Vec3(x, y, z)))
                }
                trimmed.startsWith("pattern:") -> {
                    val actionRl = ResourceLocation.tryParse(trimmed.substring(8).trim()) ?: return null
                    val entry = HexActions.REGISTRY.get(actionRl) ?: return null
                    iotaToNbt(PatternIota(entry.prototype()))
                }
                (trimmed.startsWith("pattern{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("pattern[") && trimmed.endsWith("]")) -> {
                    val inner = trimmed.substring(8, trimmed.length - 1)
                    val parts = inner.split(",")
                    val dir = HexDir.valueOf(parts[0].trim().uppercase())
                    val pat = patternFromAngles(parts[1].trim(), dir)
                    iotaToNbt(PatternIota(pat))
                }
                trimmed.startsWith("iota:[") && trimmed.endsWith("]") -> {
                    val inner = trimmed.substring(6, trimmed.length - 1)
                    val elems = ArrayList<Iota>()
                    for (part in splitTopLevel(inner)) {
                        if (part.isEmpty()) continue
                        val tag = resolveString(part, presetIotas) ?: return null
                        // 便携构建仅包含字面量/图案/向量/列表，无需 ServerLevel
                        elems.add(iotaFromNbt(tag) ?: return null)
                    }
                    iotaToNbt(ListIota(elems))
                }
                trimmed.startsWith("iota:") -> {
                    var body = trimmed.substring(5)
                    if (body.endsWith("**n")) body = body.dropLast(3)
                    body = stripColorSuffix(body)
                    if (body.endsWith(".json")) {
                        resolveJsonRef(body, presetIotas)
                    } else {
                        decodeA85(body)
                    }
                }
                trimmed.startsWith("{") -> TagParser.parseTag(trimmed)
                else -> null
            }
        } catch (e: Exception) {
            Abadoned_greatwork.LOGGER.warn("[HexPonder] 解析字符串 iota 失败: {}", rawInput, e)
            null
        }
    }

    private fun stripColorSuffix(s: String): String {
        if (s.endsWith("*b") || s.endsWith("*w")) return s.dropLast(2)
        if (s.length >= 7 && s[s.length - 7] == '*') {
            val hex = s.substring(s.length - 6)
            if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return s.dropLast(7)
            }
        }
        return s
    }

    private fun resolveJsonRef(ref: String, presetIotas: Map<ResourceLocation, String>): CompoundTag? {
        val colon = ref.lastIndexOf(':')
        val ns = if (colon > 0) ref.substring(0, colon) else Abadoned_greatwork.MODID
        val path = if (colon > 0) ref.substring(colon + 1) else ref
        val cleanName = if (path.endsWith(".json")) path else "$path.json"

        // 1) 优先从数据包缓存读取（尝试目标 ns 以及迁移兼容命名空间）
        val candidates = listOfNotNull(
            ResourceLocation.tryParse("$ns:$cleanName"),
            if (ns == "hexguide" || colon <= 0) ResourceLocation.tryParse("${Abadoned_greatwork.MODID}:$cleanName") else null,
            if (colon <= 0) ResourceLocation.tryParse("hexguide:$cleanName") else null,
        )
        for (rl in candidates) {
            val raw = presetIotas[rl]
            if (raw != null) return parsePresetJson(raw)
        }

        // 2) 回退游戏目录 <gameDir>/<ns>/iotas/<name>.json
        val fileCandidates = listOf(
            Platform.getGameFolder().resolve(ns).resolve("iotas").resolve(cleanName),
            Platform.getGameFolder().resolve(Abadoned_greatwork.MODID).resolve("hex_ponder").resolve("iotas").resolve(cleanName),
            Platform.getGameFolder().resolve("hexguide").resolve("iotas").resolve(cleanName),
        )
        for (file in fileCandidates) {
            if (Files.exists(file)) {
                return parsePresetJson(Files.readString(file))
            }
        }
        return null
    }

    private fun parsePresetJson(jsonStr: String): CompoundTag? {
        val elem = JsonParser.parseString(jsonStr)
        if (elem.isJsonObject && elem.asJsonObject.has("nbt")) {
            return TagParser.parseTag(elem.asJsonObject.get("nbt").asString)
        }
        return TagParser.parseTag(elem.toString())
    }

    /** 按括号深度分割顶层逗号，防止 vec:{1,2,3} / pattern{...} / 嵌套 iota:[...] 内部分割错乱。 */
    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when (c) {
                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    fun encodeToA85(iota: Iota): String {
        val tag = iotaToNbt(iota)
        val rawBytes = ByteArrayOutputStream().use { baos ->
            NbtIo.write(tag, DataOutputStream(baos))
            baos.toByteArray()
        }
        val compressed = ByteArrayOutputStream().use { out ->
            DeflaterOutputStream(out).use { it.write(rawBytes) }
            out.toByteArray()
        }
        return encodeA85Bytes(compressed)
    }

    fun decodeA85(encoded: String): CompoundTag? {
        val comp = decodeA85Bytes(encoded)
        val out = ByteArrayOutputStream()
        InflaterOutputStream(out).use { it.write(comp) }
        return NbtIo.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))
    }

    private fun encodeA85Bytes(d: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 4 <= d.size) {
            var v = ((d[i++].toLong() and 0xFFL) shl 24) or
                ((d[i++].toLong() and 0xFFL) shl 16) or
                ((d[i++].toLong() and 0xFFL) shl 8) or
                (d[i++].toLong() and 0xFFL)
            if (v == 0L) {
                sb.append('z')
                continue
            }
            sb.append((A85_BASE + v / 52200625L).toInt().toChar()); v %= 52200625L
            sb.append((A85_BASE + v / 614125L).toInt().toChar()); v %= 614125L
            sb.append((A85_BASE + v / 7225L).toInt().toChar()); v %= 7225L
            sb.append((A85_BASE + v / 85L).toInt().toChar()); v %= 85L
            sb.append((A85_BASE + v).toInt().toChar())
        }
        if (i < d.size) {
            var v = 0L
            val rem = d.size - i
            for (j in i until d.size) {
                v = (v shl 8) or (d[j].toLong() and 0xFFL)
            }
            v = v shl ((4 - rem) * 8)
            for (j in 0..rem) {
                sb.append((A85_BASE + v / 52200625L).toInt().toChar())
                v = (v % 52200625L) * 85L
            }
        }
        return sb.toString()
    }

    private fun decodeA85Bytes(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        val g = IntArray(5)
        var gi = 0
        for (c in s) {
            if (c == 'z') {
                out.write(ByteArray(4), 0, 4)
                gi = 0
                continue
            }
            g[gi++] = c.code - A85_BASE
            if (gi == 5) {
                val v = (((g[0] * 85L + g[1]) * 85L + g[2]) * 85L + g[3]) * 85L + g[4]
                out.write((v shr 24).toInt())
                out.write((v shr 16).toInt())
                out.write((v shr 8).toInt())
                out.write((v and 0xFFL).toInt())
                gi = 0
            }
        }
        if (gi > 0) {
            for (j in gi until 5) g[j] = 84
            val v = (((g[0] * 85L + g[1]) * 85L + g[2]) * 85L + g[3]) * 85L + g[4]
            for (j in 0 until gi - 1) {
                out.write(((v shr (24 - 8 * j)) and 0xFFL).toInt())
            }
        }
        return out.toByteArray()
    }
}
