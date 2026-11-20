package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.iota.Iota
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

/**
 * 范围切换环件（`ambit_switch`）的方块实体：
 * - 保存目标频道（`0..15` 或自定义 Iota）与最近一次重塑的平面面积 / 高度 / 体积摘要；
 * - 支持手持锚点绑定频道与 IoticBlocks Iota 读写。
 */
class AmbitSwitchBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADIotaHolder {

    val channelState = WirelessChannelState {
        setChanged()
        sync()
    }

    var lastAreaBlocks: Long = 0L
        private set

    var lastHeight: Int = 0
        private set

    var lastVolumeBlocks: Long = 0L
        private set

    fun recordShapeSummary(area: Long, height: Int, volume: Long) {
        if (lastAreaBlocks == area && lastHeight == height && lastVolumeBlocks == volume) return
        lastAreaBlocks = area
        lastHeight = height
        lastVolumeBlocks = volume
        setChanged()
        sync()
    }

    override fun saveModData(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        channelState.save(tag)
        tag.putLong(TAG_LAST_AREA, lastAreaBlocks)
        tag.putInt(TAG_LAST_HEIGHT, lastHeight)
        tag.putLong(TAG_LAST_VOL, lastVolumeBlocks)
    }

    override fun loadModData(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        channelState.load(tag)
        lastAreaBlocks = tag.getLong(TAG_LAST_AREA)
        lastHeight = tag.getInt(TAG_LAST_HEIGHT)
        lastVolumeBlocks = tag.getLong(TAG_LAST_VOL)
    }

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>) {
        channelState.appendLensLines(lines)
        if (lastVolumeBlocks > 0L) {
            lines.add(
                Pair(
                    ItemStack(Items.MAP),
                    Component.translatable(
                        "text.abadoned_greatwork.ambit.switch_summary",
                        Component.literal(lastAreaBlocks.toString()).withStyle(ChatFormatting.AQUA),
                        Component.literal(lastHeight.toString()).withStyle(ChatFormatting.AQUA),
                        Component.literal(lastVolumeBlocks.toString()).withStyle(ChatFormatting.LIGHT_PURPLE),
                    ),
                )
            )
        } else {
            lines.add(
                Pair(
                    ItemStack(Items.MAP),
                    Component.translatable("text.abadoned_greatwork.ambit.switch_default"),
                )
            )
        }
    }

    // ==================== ADIotaHolder ====================

    override fun readIota(): at.petrak.hexcasting.api.casting.iota.Iota = channelState.readIota(level?.registryAccess())

    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean = channelState.writeIota(iota, simulate, level?.registryAccess())

    override fun writeable(): Boolean = true

    companion object {
        private const val TAG_LAST_AREA = "last_area"
        private const val TAG_LAST_HEIGHT = "last_height"
        private const val TAG_LAST_VOL = "last_volume"
    }
}
