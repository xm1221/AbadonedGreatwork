package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitNetwork
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

    val channelState = WirelessChannelState { onChannelOrOrderDirty() }

    private fun onChannelOrOrderDirty() {
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                AmbitNetwork.updateAnchor(srv, worldPosition, channelState.key(), order)
            }
        }
        setChanged()
        sync()
    }

    fun applyPlacedBinding(channelTag: CompoundTag?, allocatedOrder: Long) {
        if (channelTag != null) {
            channelState.load(channelTag)
        }
        order = allocatedOrder.coerceAtLeast(1L)
        onChannelOrOrderDirty()
    }

    fun applyNormalizedOrder(normalizedOrder: Long) {
        if (order == normalizedOrder) return
        order = normalizedOrder
        setChanged()
        sync()
    }

    fun reassignChannelAndOrder(delta: Int): kotlin.Pair<Int, Int> {
        channelState.cycleChannel(delta)
        val srv = level as? ServerLevel ?: return 1 to 1
        val nextOrder = AmbitNetwork.allocateAppendOrder(srv, channelState.key())
        order = nextOrder
        AmbitNetwork.updateAnchor(srv, worldPosition, channelState.key(), order)
        setChanged()
        sync()
        return AmbitNetwork.getAnchorRank(srv, worldPosition, channelState.key())
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (!isRemoved) {
            (level as? ServerLevel)?.let {
                AmbitNetwork.registerAnchor(it, worldPosition, channelState.key(), order)
            }
        }
    }

    override fun clearRemoved() {
        super.clearRemoved()
        val srv = level as? ServerLevel ?: return
        AmbitNetwork.registerAnchor(srv, worldPosition, channelState.key(), order)
    }

    override fun setRemoved() {
        (level as? ServerLevel)?.let { AmbitNetwork.unregisterAnchor(it, worldPosition) }
        super.setRemoved()
    }

    override fun saveModData(tag: CompoundTag) {
        channelState.save(tag)
        tag.putLong(TAG_ORDER, order)
    }

    override fun loadModData(tag: CompoundTag) {
        channelState.load(tag)
        order = if (tag.contains(TAG_ORDER)) tag.getLong(TAG_ORDER).coerceAtLeast(1L) else 1L
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                AmbitNetwork.registerAnchor(srv, worldPosition, channelState.key(), order)
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
        val ok = channelState.writeIota(iota, false)
        if (ok) {
            val srv = level as? ServerLevel
            if (srv != null && !isRemoved) {
                order = AmbitNetwork.allocateAppendOrder(srv, channelState.key())
                AmbitNetwork.updateAnchor(srv, worldPosition, channelState.key(), order)
                setChanged()
                sync()
            }
        }
        return ok
    }

    override fun writeable(): Boolean = true

    companion object {
        private const val TAG_ORDER = "ambit_order"
    }
}
