package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.casting.circles.TransmitResult
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessChannelState
import com.mojang.datafixers.util.Pair
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.roundToInt

/**
 * 无线发信石的方块实体：
 * - 存谐振频道（`0..15` 或自定义 Iota）、最近一次辐射命中的接收端数量与有效辐射半径；
 * - 为客户端旗帜升降动画保留纯瞬态平滑进度状态。
 */
class WaveTransmitterBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADIotaHolder {

    val channelState = WirelessChannelState {
        setChanged()
        sync()
    }

    var lastTriggeredCount: Int = 0
        private set

    var lastRangeBlocks: Int = 0
        private set

    // 客户端动画瞬态字段（不落盘、不发包）
    @Transient
    var clientRaiseProgress: Float = 0f

    @Transient
    var clientLastRenderTime: Double = -1.0

    @Transient
    var clientHoldUntilTime: Double = -1.0

    fun recordTransmitResult(result: TransmitResult) {
        val roundedRange = result.maxRange.roundToInt()
        if (lastTriggeredCount == result.triggeredCount && lastRangeBlocks == roundedRange) return
        lastTriggeredCount = result.triggeredCount
        lastRangeBlocks = roundedRange
        setChanged()
        sync()
    }

    override fun saveModData(tag: CompoundTag) {
        channelState.save(tag)
        tag.putInt(TAG_LAST_COUNT, lastTriggeredCount)
        tag.putInt(TAG_LAST_RANGE, lastRangeBlocks)
    }

    override fun loadModData(tag: CompoundTag) {
        channelState.load(tag)
        lastTriggeredCount = tag.getInt(TAG_LAST_COUNT)
        lastRangeBlocks = tag.getInt(TAG_LAST_RANGE)
    }

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>, state: BlockState) {
        channelState.appendLensLines(lines)
        lines.add(
            Pair(
                ItemStack(Items.SPYGLASS),
                Component.translatable(
                    "text.abadoned_greatwork.wireless.last_range",
                    Component.literal(lastRangeBlocks.toString()).withStyle(ChatFormatting.AQUA),
                ),
            )
        )
        lines.add(
            Pair(
                ItemStack(Items.REDSTONE_TORCH),
                Component.translatable("text.abadoned_greatwork.wireless.last_hits", lastTriggeredCount),
            )
        )
    }

    // ==================== ADIotaHolder ====================

    override fun readIotaTag(): CompoundTag = channelState.readIotaTag()

    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean = channelState.writeIota(iota, simulate)

    override fun writeable(): Boolean = true

    companion object {
        private const val TAG_LAST_COUNT = "last_triggered_count"
        private const val TAG_LAST_RANGE = "last_range_blocks"
    }
}
