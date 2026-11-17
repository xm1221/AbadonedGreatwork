package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitNetwork
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitOrderCounter
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessChannelState
import com.mojang.datafixers.util.Pair
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * 范围锚点（`ambit_anchor`）的方块实体：
 * - 存储所属频道（`WirelessChannelState`）与同频连线顺位 `order`；
 * - 注册进 [AmbitNetwork] 供范围内的切换石按顺位闭合构形。
 */
class AmbitAnchorBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADIotaHolder {

    var order: Long = 1L
        private set

    /**
     * 频道或顺位正在被整体改写时置位，用来压掉中途那次"半成品"索引写入。
     *
     * 典型场景：换频道时 `channelState.cycleChannel` 会先触发 dirty，此刻 `order` 还是旧频道
     * 的号；若不压掉，本次锚点会带着旧号短暂进入新频道桶，而 [AmbitNetwork.allocateAppendOrder]
     * 随后会把那个旧号当成"新频道已有的最大值"，导致顺位号一路膨胀。
     */
    private var suppressDirty = false

    val channelState = WirelessChannelState { onChannelOrOrderDirty() }

    private fun onChannelOrOrderDirty() {
        if (suppressDirty) return
        pushToNetwork()
    }

    /** 把当前频道与顺位同步进 [AmbitNetwork] 并落盘。 */
    private fun pushToNetwork() {
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                AmbitNetwork.updateAnchor(srv, worldPosition, channelState.key(), order, this)
            }
        }
        setChanged()
        sync()
    }

    fun applyPlacedBinding(channelTag: CompoundTag?, allocatedOrder: Long) {
        suppressDirty = true
        try {
            if (channelTag != null) {
                channelState.load(channelTag)
            }
            order = allocatedOrder.coerceAtLeast(1L)
        } finally {
            suppressDirty = false
        }
        pushToNetwork()
    }

    /**
     * 接受 [AmbitNetwork] 规整后的顺位号。
     *
     * 只允许 [AmbitNetwork] 调用——顺位的唯一权威源在那边，这里只负责把结果落进 BE 字段与存档。
     */
    fun applyNormalizedOrder(normalizedOrder: Long) {
        if (order == normalizedOrder) return
        order = normalizedOrder
        setChanged()
        sync()
    }

    fun reassignChannelAndOrder(delta: Int): kotlin.Pair<Int, Int> {
        val srv = level as? ServerLevel ?: return 1 to 1
        suppressDirty = true
        try {
            channelState.cycleChannel(delta)
            order = AmbitNetwork.allocateAppendOrder(srv, channelState.key())
        } finally {
            suppressDirty = false
        }
        // 频道与顺位都定下来了，一次性写进索引
        pushToNetwork()
        return AmbitNetwork.getAnchorRank(srv, worldPosition, channelState.key())
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (!isRemoved) {
            (level as? ServerLevel)?.let { registerSelf(it) }
        }
    }

    override fun clearRemoved() {
        super.clearRemoved()
        val srv = level as? ServerLevel ?: return
        registerSelf(srv)
    }

    /**
     * 注销自己。
     *
     * 不能只按坐标删——`LevelChunk.setBlockEntity` 的顺序是"新 BE 先 `setLevel`+`clearRemoved`
     * 注册，再 `put` 换出旧 BE，最后才 `setRemoved` 旧 BE"。同坐标换 BE（世界升级、结构粘贴、
     * 方块复用）时，旧 BE 这一下会把新 BE 刚写进去的条目删掉，此后该锚点在索引里彻底消失。
     *
     * 这里传 `this` 做归属校验：只有索引里记的还是自己才真正删除。刻意不调 `Level.getBlockEntity`
     * 反查现任者，因为那个方法默认 `EntityCreationType.IMMEDIATE`，可能在 `setRemoved` 期间
     * 顺手把一个新 BE 建出来。
     */
    override fun setRemoved() {
        (level as? ServerLevel)?.let { AmbitNetwork.unregisterAnchor(it, worldPosition, this) }
        super.setRemoved()
    }

    /** 注册进索引，并把自身顺位号报到持久计数器，防止后续分配撞上已存在的号。 */
    private fun registerSelf(srv: ServerLevel) {
        val key = channelState.key()
        AmbitNetwork.registerAnchor(srv, worldPosition, key, order, this)
        AmbitOrderCounter.of(srv).observe(key, order)
    }

    override fun saveModData(tag: CompoundTag) {
        channelState.save(tag)
        tag.putLong(TAG_ORDER, order)
    }

    override fun loadModData(tag: CompoundTag) {
        channelState.load(tag)
        order = if (tag.contains(TAG_ORDER)) tag.getLong(TAG_ORDER).coerceAtLeast(1L) else 1L
        // 正常加载链路里 load() 早于 setLevel()，此刻 level 还是 null；
        // 真正入世的注册由 setLevel/clearRemoved 负责，这里只为"重载到已有 level"兜底。
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                registerSelf(srv)
            }
        }
    }

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>) {
        channelState.appendLensLines(lines)
        lines.add(
            Pair(
                ItemStack(Items.NAME_TAG),
                Component.translatable(
                    "text.abadoned_greatwork.ambit.anchor_order",
                    Component.literal("#$order").withStyle(ChatFormatting.AQUA),
                ),
            )
        )
    }

    // ==================== ADIotaHolder ====================

    override fun readIotaTag(): CompoundTag = channelState.readIotaTag()

    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean {
        if (simulate) return true
        val srv = level as? ServerLevel
        // 写 Iota 会改频道，同样要在"频道与顺位一起定好"之后才更新索引，
        // 否则中途那次脏写会把旧频道的顺位号带进新频道。
        suppressDirty = srv != null
        try {
            val ok = channelState.writeIota(iota, false)
            if (!ok) return false
            if (srv == null || isRemoved) return true
            order = AmbitNetwork.allocateAppendOrder(srv, channelState.key())
        } finally {
            suppressDirty = false
        }
        pushToNetwork()
        return true
    }

    override fun writeable(): Boolean = true

    companion object {
        private const val TAG_ORDER = "ambit_order"
    }
}
