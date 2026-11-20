package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADMediaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.api.utils.extractMedia
import at.petrak.hexcasting.common.lib.HexItems
import com.mojang.datafixers.util.Pair
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * 媒质池（`media_pool`）的方块实体：
 * - 储存大量临时媒质（默认上限 `7680` 紫水晶粉，造物解除器可注入无限态 `-1L`）；
 * - 媒质波穿过时从栈顶取出数字：正数向促动石（合流多石则平分）注入对应粉量，负数从促动石抽取粉量充入自身；
 * - 客户端通过 [clientDisplayedFill] 对液面高度变化做平滑插值。
 */
class MediaPoolBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADMediaHolder {

    private var storedMedia: Long = 0L

    /** 客户端渲染用的平滑液面占比缓存（`< 0f` 表示首次渲染直接对齐）。 */
    @Transient
    var clientDisplayedFill: Float = -1.0f

    @Transient
    var clientLastRenderTime: Double = -1.0

    fun isUnlimited(): Boolean = storedMedia < 0L

    fun fillRatio(): Float = when {
        storedMedia < 0L -> 1.0f
        storedMedia <= 0L -> 0.0f
        else -> (storedMedia.toDouble() / MAX_MEDIA.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
    }

    fun hasMediaForInsertion(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (storedMedia < 0L) return false
        if (stack.`is`(HexItems.CREATIVE_UNLOCKER.get())) return true
        return extractMediaFromInsertedItem(stack, true) > 0L
    }

    fun extractMediaFromInsertedItem(stack: ItemStack, simulate: Boolean): Long {
        if (storedMedia < 0L) return 0L
        val remaining = (MAX_MEDIA - storedMedia).coerceAtLeast(0L)
        if (remaining <= 0L) return 0L
        return extractMedia(stack, remaining, true, simulate)
    }

    /**
     * 玩家手持媒质物品或造物解除器右击时向池内充能；真正写入媒质则返回 `true`。
     */
    fun insertMediaFromItem(stack: ItemStack): Boolean {
        if (stack.isEmpty || storedMedia < 0L) return false
        if (stack.`is`(HexItems.CREATIVE_UNLOCKER.get())) {
            storedMedia = -1L
            markDirtyAndSync()
            return true
        }
        val extracted = extractMediaFromInsertedItem(stack, false)
        if (extracted <= 0L) return false
        storedMedia = (storedMedia + extracted).coerceAtMost(MAX_MEDIA)
        markDirtyAndSync()
        return true
    }

    /**
     * 向本次波关联的促动石集合（合流多促动石时按水桶平分）注入 [amount] 单位媒质。
     * 池内有限媒质不足 [amount] 时返回 `false`（不扣除池内媒质），交由调用方触发媒质不足事故。
     */
    fun dispenseToImpetuses(amount: Long, impetuses: List<BlockEntityAbstractImpetus>): Boolean {
        if (amount <= 0L) return true
        if (storedMedia >= 0L && storedMedia < amount) return false

        if (storedMedia >= 0L) {
            storedMedia -= amount
            markDirtyAndSync()
        }

        val addedMap = HashMap<BlockEntityAbstractImpetus, Long>(impetuses.size)
        var left = amount
        while (left > 0L) {
            val eligible = impetuses.filter { imp ->
                val cur = imp.getMedia()
                if (cur < 0L) return@filter false
                val extra = addedMap[imp] ?: 0L
                (imp.remainingMediaCapacity() - extra) > 0L
            }
            if (eligible.isEmpty()) break

            val base = left / eligible.size
            val rem = (left % eligible.size).toInt()
            var roundMoved = 0L
            for ((idx, imp) in eligible.withIndex()) {
                val want = base + if (idx < rem) 1L else 0L
                if (want <= 0L) continue
                val extra = addedMap[imp] ?: 0L
                val cap = (imp.remainingMediaCapacity() - extra).coerceAtLeast(0L)
                val give = minOf(want, cap)
                if (give > 0L) {
                    addedMap[imp] = extra + give
                    roundMoved += give
                }
            }
            if (roundMoved <= 0L) break
            left -= roundMoved
        }

        for ((imp, added) in addedMap) {
            if (added > 0L) {
                imp.setMedia(imp.getMedia() + added)
                imp.sync()
            }
        }
        return true
    }

    /**
     * 从本次波关联的促动石集合中抽取 [amount] 单位媒质充入本池（多个有限促动石按余量均摊）。
     * 若所有促动石均为有限且总余量不足 [amount]，返回 `false` 且不做扣除，交由调用方触发事故。
     */
    fun chargeFromImpetuses(amount: Long, impetuses: List<BlockEntityAbstractImpetus>): Boolean {
        if (amount <= 0L) return true
        if (impetuses.isEmpty()) return false

        val hasUnlimitedImpetus = impetuses.any { it.getMedia() < 0L }
        if (!hasUnlimitedImpetus) {
            var available = 0L
            for (imp in impetuses) {
                val cur = imp.getMedia().coerceAtLeast(0L)
                available = if (Long.MAX_VALUE - available < cur) Long.MAX_VALUE else available + cur
                if (available >= amount) break
            }
            if (available < amount) return false

            val takenMap = HashMap<BlockEntityAbstractImpetus, Long>(impetuses.size)
            var left = amount
            while (left > 0L) {
                val contributors = impetuses.filter { imp ->
                    val already = takenMap[imp] ?: 0L
                    (imp.getMedia() - already) > 0L
                }
                if (contributors.isEmpty()) break

                val base = left / contributors.size
                val rem = (left % contributors.size).toInt()
                var roundTaken = 0L
                for ((idx, imp) in contributors.withIndex()) {
                    val want = base + if (idx < rem) 1L else 0L
                    if (want <= 0L) continue
                    val already = takenMap[imp] ?: 0L
                    val canTake = (imp.getMedia() - already).coerceAtLeast(0L)
                    val take = minOf(want, canTake)
                    if (take > 0L) {
                        takenMap[imp] = already + take
                        roundTaken += take
                    }
                }
                if (roundTaken <= 0L) break
                left -= roundTaken
            }

            for ((imp, taken) in takenMap) {
                if (taken > 0L) {
                    imp.setMedia((imp.getMedia() - taken).coerceAtLeast(0L))
                    imp.sync()
                }
            }
        }

        if (storedMedia >= 0L) {
            val next = if (Long.MAX_VALUE - storedMedia < amount) MAX_MEDIA else (storedMedia + amount).coerceAtMost(MAX_MEDIA)
            if (next != storedMedia) {
                storedMedia = next
                markDirtyAndSync()
            }
        }
        return true
    }

    fun analogOutputSignal(): Int {
        if (storedMedia < 0L) return 15
        if (storedMedia <= 0L) return 0
        return ((storedMedia.toDouble() / MAX_MEDIA) * 14.0).roundToInt().coerceIn(1, 15)
    }

    private fun markDirtyAndSync() {
        setChanged()
        sync()
        level?.updateNeighbourForOutputSignal(worldPosition, blockState.block)
    }

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>) {
        val line = if (storedMedia < 0L) {
            Component.translatable("text.abadoned_greatwork.media_pool.unlimited")
        } else {
            val cur = DUST_FMT.format(storedMedia.toDouble() / MediaConstants.DUST_UNIT)
            val max = DUST_FMT.format(MAX_MEDIA.toDouble() / MediaConstants.DUST_UNIT)
            Component.translatable(
                "text.abadoned_greatwork.media_pool.stored",
                Component.literal(cur).withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal(max).withStyle(ChatFormatting.GRAY),
            )
        }
        lines.add(Pair(ItemStack(HexItems.AMETHYST_DUST.get()), line))
    }

    override fun saveModData(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        tag.putLong(TAG_MEDIA, storedMedia)
    }

    override fun loadModData(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        storedMedia = if (tag.contains(TAG_MEDIA)) {
            val raw = tag.getLong(TAG_MEDIA)
            if (raw < 0L) -1L else raw.coerceAtMost(MAX_MEDIA)
        } else {
            0L
        }
    }

    // ==================== ADMediaHolder ====================

    override fun getMedia(): Long = if (storedMedia < 0L) MAX_MEDIA else storedMedia

    override fun getMaxMedia(): Long = MAX_MEDIA

    override fun setMedia(media: Long) {
        val normalized = if (media < 0L) -1L else media.coerceIn(0L, MAX_MEDIA)
        if (storedMedia == normalized) return
        storedMedia = normalized
        markDirtyAndSync()
    }

    override fun canRecharge(): Boolean = storedMedia >= 0L && storedMedia < MAX_MEDIA

    override fun canProvide(): Boolean = storedMedia != 0L

    override fun getConsumptionPriority(): Int = ADMediaHolder.BATTERY_PRIORITY

    override fun canConstructBattery(): Boolean = false

    override fun withdrawMedia(cost: Long, simulate: Boolean): Long {
        if (cost <= 0L || storedMedia == 0L) return 0L
        if (storedMedia < 0L) return cost
        val pulled = minOf(cost, storedMedia)
        if (!simulate && pulled > 0L) {
            storedMedia -= pulled
            markDirtyAndSync()
        }
        return pulled
    }

    override fun insertMedia(amount: Long, simulate: Boolean): Long {
        if (amount <= 0L || storedMedia < 0L) return 0L
        val accepted = minOf(amount, (MAX_MEDIA - storedMedia).coerceAtLeast(0L))
        if (!simulate && accepted > 0L) {
            storedMedia += accepted
            markDirtyAndSync()
        }
        return accepted
    }

    companion object {
        const val MAX_MEDIA: Long = 7680L * MediaConstants.DUST_UNIT
        private const val TAG_MEDIA = "media"
        private val DUST_FMT = DecimalFormat("0.##")
    }
}
