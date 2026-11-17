package cn.xm1221.abadoned_greatwork.casting.circles

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

/**
 * 每个频道已分配过的最大顺位号（`order`）的持久计数器。
 *
 * <h2>为什么需要它</h2>
 * `order` 之前是"扫一遍当前已加载的锚点，取最大值 +1"分配的。但同频道的锚点可能分属
 * 不同区块并**独立卸载**：卸载期间它们不在 [AmbitNetwork] 的索引里，于是新分配的号会撞上
 * 它们已经持久化在 NBT 里的旧号。撞号后 [AmbitNetwork.getOrderedAnchors] 只能拿坐标兜底
 * 排序，顶点顺序就偏离了玩家的摆放意图——而且这种偏移是静默的，玩家看不到任何提示。
 *
 * 计数器只增不减、且落盘到世界存档，所以新号一定大于任何**曾经**分配过的号，
 * 无论那个锚点此刻是否加载。
 *
 * <h2>为什么不在频道空掉时回收</h2>
 * 因为 `setRemoved()` 在**区块卸载**时也会触发，那时频道"看起来"空了，但其实锚点还在世界里。
 * 回收会立刻让计数归零，下一轮分配重新撞号——比不回收更糟。编号只随玩家的放置次数增长，
 * 且 [AmbitNetwork.rotateAnchorOrder] 规整时会把已加载锚点压回 `1..N`。
 */
class AmbitOrderCounter private constructor(
    /** 整数频道 `0..15` 的计数器，键是频道号。 */
    private val numericCounters: CompoundTag,
    /** 自定义 Iota 频道的计数器，每项 `{iota, highest}`。 */
    private val customCounters: ListTag,
) : SavedData() {

    /** 取该频道下一个可用顺位号（单调递增，永不复用）。 */
    fun allocate(key: WirelessChannelKey): Long {
        val next = highest(key) + 1L
        put(key, next)
        setDirty()
        return next
    }

    /**
     * 记下 [order] 已被占用。
     *
     * 用于把旧存档里已存在的顺位号纳入计数，避免首次分配时与它们相撞。
     * 只在抬升上限时写盘，因此重复调用几乎没有成本。
     */
    fun observe(key: WirelessChannelKey, order: Long) {
        if (order <= 0L) return
        if (highest(key) >= order) return
        put(key, order)
        setDirty()
    }

    private fun highest(key: WirelessChannelKey): Long = when (key) {
        is WirelessChannelKey.Numeric ->
            if (numericCounters.contains(channelId(key))) numericCounters.getLong(channelId(key)) else 0L

        is WirelessChannelKey.CustomIota -> {
            var found = 0L
            for (i in 0 until customCounters.size) {
                val entry = customCounters.getCompound(i)
                if (!entry.contains(TAG_IOTA, Tag.TAG_COMPOUND.toInt())) continue
                // CompoundTag.equals 与 Map 的键序无关，可以安全地用来匹配同一个 Iota
                if (entry.getCompound(TAG_IOTA) == key.tag) {
                    found = entry.getLong(TAG_HIGHEST)
                    break
                }
            }
            found
        }
    }

    private fun put(key: WirelessChannelKey, order: Long) {
        when (key) {
            is WirelessChannelKey.Numeric -> numericCounters.putLong(channelId(key), order)

            is WirelessChannelKey.CustomIota -> {
                for (i in 0 until customCounters.size) {
                    val entry = customCounters.getCompound(i)
                    if (!entry.contains(TAG_IOTA, Tag.TAG_COMPOUND.toInt())) continue
                    if (entry.getCompound(TAG_IOTA) == key.tag) {
                        // 显式写回，不依赖 getCompound 返回的是内部引用
                        val updated = entry.copy()
                        updated.putLong(TAG_HIGHEST, order)
                        customCounters.set(i, updated)
                        return
                    }
                }
                val entry = CompoundTag()
                entry.put(TAG_IOTA, key.tag.copy())
                entry.putLong(TAG_HIGHEST, order)
                customCounters.add(entry)
            }
        }
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.put(TAG_NUMERIC, numericCounters.copy())
        tag.put(TAG_CUSTOM, customCounters.copy())
        return tag
    }

    companion object {
        private const val TAG_NUMERIC = "numeric"
        private const val TAG_CUSTOM = "custom"
        private const val TAG_IOTA = "iota"
        private const val TAG_HIGHEST = "highest"
        private const val DATA_NAME = "abadoned_greatwork_ambit_order"

        fun of(level: ServerLevel): AmbitOrderCounter =
            level.dataStorage.computeIfAbsent(
                { tag -> fromTag(tag) },
                { AmbitOrderCounter(CompoundTag(), ListTag()) },
                DATA_NAME,
            )

        private fun fromTag(tag: CompoundTag): AmbitOrderCounter {
            val numeric = if (tag.contains(TAG_NUMERIC, Tag.TAG_COMPOUND.toInt())) {
                tag.getCompound(TAG_NUMERIC).copy()
            } else {
                CompoundTag()
            }
            val custom = if (tag.contains(TAG_CUSTOM, Tag.TAG_LIST.toInt())) {
                tag.getList(TAG_CUSTOM, Tag.TAG_COMPOUND.toInt()).copy()
            } else {
                ListTag()
            }
            return AmbitOrderCounter(numeric, custom)
        }

        private fun channelId(key: WirelessChannelKey.Numeric): String = "ch${key.channel}"
    }
}
