package cn.xm1221.abadoned_greatwork.block

import com.google.gson.Gson
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import java.io.InputStreamReader

/**
 * 数据驱动的谜题加载器。
 * 从 `data/<ns>/riddles/` 读取谜题定义，解析 SNBT 字符串为 [CompoundTag]。
 */
object Abadoned_greatworkRiddleLoader {

    data class RawRiddle(
        val name_key: String = "",
        val input0: String = "",
        val input1: String = "",
        val output0: String = "",
        val output1: String = "",
        val length_limit: Int = 0,
        val variant: Int = 0,
        /** 完整 ItemStack NBT（SNBT 字符串）。若不为空，优先使用此字段，忽略其他结构化字段。 */
        val nbt: String = "",
    ) {
        /** 是否使用完整 NBT 模式 */
        val isRawNbt: Boolean get() = nbt.isNotBlank()

        fun nbtTag(): CompoundTag? = parseTag(nbt)
        fun input0Tag(): CompoundTag? = parseTag(input0)
        fun input1Tag(): CompoundTag? = parseTag(input1)
        fun output0Tag(): CompoundTag? = parseTag(output0)
        fun output1Tag(): CompoundTag? = parseTag(output1)

        private fun parseTag(snbt: String): CompoundTag? {
            if (snbt.isBlank()) return null
            return try {
                TagParser.parseTag(snbt) as? CompoundTag
            } catch (_: Exception) {
                null
            }
        }
    }

    private val gson = Gson()
    private var cached: List<RawRiddle>? = null

    val riddles: List<RawRiddle> get() {
        cached?.let { return it }
        cached = load()
        return cached ?: emptyList()
    }

    private fun load(): List<RawRiddle> {
        val result = mutableListOf<RawRiddle>()
        val prefix = "data/abadoned_greatwork/riddles/"
        try {
            val classLoader = javaClass.classLoader
            val urls = classLoader.getResources(prefix) ?: return result
            while (urls.hasMoreElements()) {
                val url = urls.nextElement()
                if (url.protocol == "jar") {
                    val conn = url.openConnection()
                    val jarFile = (conn as? java.net.JarURLConnection)?.jarFile
                    jarFile?.entries()?.asIterator()?.forEach { entry ->
                        val name = entry.name
                        if (name.startsWith(prefix) && name.endsWith(".json")) {
                            jarFile.getInputStream(entry).use { stream ->
                                val riddle = gson.fromJson(InputStreamReader(stream), RawRiddle::class.java)
                                if (riddle != null) result.add(riddle)
                            }
                        }
                    }
                } else {
                    java.io.File(url.toURI()).listFiles()?.forEach { file ->
                        if (file.name.endsWith(".json")) {
                            val riddle = gson.fromJson(file.reader(), RawRiddle::class.java)
                            if (riddle != null) result.add(riddle)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return result
    }
}
