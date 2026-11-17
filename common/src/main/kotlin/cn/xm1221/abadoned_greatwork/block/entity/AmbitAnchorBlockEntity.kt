package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.pigment.FrozenPigment
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitNetwork
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessChannelState
import com.mojang.datafixers.util.Pair
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * 道引标（`ambit_anchor`）的方块实体：
 * - 存储所属频道（`WirelessChannelState`）与**必须由玩家手动指定**的连线顺位 `order`（`0` = 未指定）；
 * - 注册进 [AmbitNetwork] 供范围内的塑界卫道石校验顺位并闭合构形；
 * - 记录激活态与顶部光球色彩（施法者定制染色优先，回落到栈顶 Iota 类型配色）。
 */
class AmbitAnchorBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADIotaHolder {

    /** 0 表示未指定顺位；有效顺位为 >= 1 的正整数。 */
    var order: Long = 0L
        private set

    var orbActive: Boolean = false
        private set

    var orbPigmentTag: CompoundTag? = null
        private set

    var orbFallbackColor: Int = DEFAULT_ORB_COLOR
        private set

    @Transient
    private var clientCachedPigmentTag: CompoundTag? = null

    @Transient
    private var clientCachedPigment: FrozenPigment? = null

    val channelState = WirelessChannelState { pushToNetwork() }

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

    fun applyPlacedBinding(channelTag: CompoundTag?) {
        if (channelTag != null) {
            channelState.load(channelTag)
        }
        order = 0L
        pushToNetwork()
    }

    /** 手动递增顺位：`0 -> 1 -> 2 -> ... -> cap -> 1`。 */
    fun cycleManualOrder(): Long {
        val srv = level as? ServerLevel
        val channelCount = if (srv != null) AmbitNetwork.countAnchorsInChannel(srv, channelState.key()) else 0
        val cap = maxOf(MAX_MANUAL_ORDER, channelCount.toLong())
        order = if (order <= 0L || order >= cap) 1L else order + 1L
        pushToNetwork()
        return order
    }

    fun setOrbActivation(active: Boolean, pigmentTag: CompoundTag?, fallbackColor: Int) {
        val nextPigment = if (active) pigmentTag?.copy() else null
        val nextColor = if (active) (fallbackColor and 0xFFFFFF) else DEFAULT_ORB_COLOR
        if (orbActive == active && orbFallbackColor == nextColor && orbPigmentTag == nextPigment) {
            return
        }
        orbActive = active
        orbPigmentTag = nextPigment
        orbFallbackColor = nextColor
        clientCachedPigmentTag = null
        clientCachedPigment = null
        setChanged()
        sync()
    }

    fun resolveClientPigment(): FrozenPigment? {
        val tag = orbPigmentTag ?: return null
        if (clientCachedPigment == null || clientCachedPigmentTag != tag) {
            clientCachedPigmentTag = tag.copy()
            clientCachedPigment = FrozenPigment.fromNBT(tag)
        }
        return clientCachedPigment
    }

    fun cycleChannel(delta: Int): Int {
        val ch = channelState.cycleChannel(delta)
        return ch
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

    override fun setRemoved() {
        (level as? ServerLevel)?.let { AmbitNetwork.unregisterAnchor(it, worldPosition, this) }
        super.setRemoved()
    }

    private fun registerSelf(srv: ServerLevel) {
        AmbitNetwork.registerAnchor(srv, worldPosition, channelState.key(), order, this)
        if (orbActive && !AmbitNetwork.isAnchorHeld(srv, worldPosition)) {
            setOrbActivation(false, null, DEFAULT_ORB_COLOR)
        }
    }

    override fun saveModData(tag: CompoundTag) {
        channelState.save(tag)
        tag.putLong(TAG_ORDER, order)
        tag.putBoolean(TAG_ORB_ACTIVE, orbActive)
        tag.putInt(TAG_ORB_COLOR, orbFallbackColor)
        orbPigmentTag?.let { tag.put(TAG_ORB_PIGMENT, it.copy()) }
    }

    override fun loadModData(tag: CompoundTag) {
        channelState.load(tag)
        order = if (tag.contains(TAG_ORDER)) tag.getLong(TAG_ORDER).coerceAtLeast(0L) else 0L
        orbActive = tag.getBoolean(TAG_ORB_ACTIVE)
        orbFallbackColor = if (tag.contains(TAG_ORB_COLOR)) tag.getInt(TAG_ORB_COLOR) and 0xFFFFFF else DEFAULT_ORB_COLOR
        orbPigmentTag = if (tag.contains(TAG_ORB_PIGMENT, Tag.TAG_COMPOUND.toInt())) {
            tag.getCompound(TAG_ORB_PIGMENT).copy()
        } else {
            null
        }
        clientCachedPigmentTag = null
        clientCachedPigment = null
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                registerSelf(srv)
            }
        }
    }

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>) {
        channelState.appendLensLines(lines)
        val orderText = if (order <= 0L) {
            Component.translatable("text.abadoned_greatwork.ambit.anchor_order_unset")
                .withStyle(ChatFormatting.YELLOW)
        } else {
            Component.literal("#$order").withStyle(ChatFormatting.AQUA)
        }
        lines.add(
            Pair(
                ItemStack(Items.NAME_TAG),
                Component.translatable("text.abadoned_greatwork.ambit.anchor_order", orderText),
            )
        )
    }

    // ==================== ADIotaHolder ====================

    override fun readIotaTag(): CompoundTag = channelState.readIotaTag()

    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean = channelState.writeIota(iota, simulate)

    override fun writeable(): Boolean = true

    companion object {
        const val DEFAULT_ORB_COLOR: Int = 0xB761F0
        const val MAX_MANUAL_ORDER: Long = 16L
        private const val TAG_ORDER = "ambit_order"
        private const val TAG_ORB_ACTIVE = "ambit_orb_active"
        private const val TAG_ORB_COLOR = "ambit_orb_color"
        private const val TAG_ORB_PIGMENT = "ambit_orb_pigment"
    }
}
