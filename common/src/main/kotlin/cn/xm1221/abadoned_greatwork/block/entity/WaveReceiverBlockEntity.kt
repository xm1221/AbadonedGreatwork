package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitReshapeHook
import cn.xm1221.abadoned_greatwork.casting.circles.ImpetusBranchHost
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessChannelState
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessWaveNetwork
import com.mojang.datafixers.util.Pair
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * 谐振收信石的方块实体（继承 [BlockEntityAbstractImpetus]）：
 * - 在 [WirelessWaveNetwork] 中按频道分桶注册，监听同频无线媒质波；
 * - 起振输出方向就是方块正面徽记朝向（`FACING`）；
 * - 遵循标准法术环闭合校验，仅在路径闭合回自身后将发信端的栈与 `userData` 注入首帧镜像执行。
 */
class WaveReceiverBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : BlockEntityAbstractImpetus(type, pos, state), ADIotaHolder {

    val channelState: WirelessChannelState = WirelessChannelState { onChannelDirty() }

    private fun onChannelDirty() {
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                WirelessWaveNetwork.updateReceiverChannel(srv, worldPosition, channelState.key())
            }
        }
        setChanged()
        sync()
    }

    private var lastReceiveTick: Long = -1L

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (!isRemoved) {
            (level as? ServerLevel)?.let { WirelessWaveNetwork.registerReceiver(it, worldPosition, channelState.key()) }
        }
    }

    override fun clearRemoved() {
        super.clearRemoved()
        val srv = level as? ServerLevel ?: return
        WirelessWaveNetwork.registerReceiver(srv, worldPosition, channelState.key())
    }

    override fun setRemoved() {
        (level as? ServerLevel)?.let { WirelessWaveNetwork.unregisterReceiver(it, worldPosition) }
        super.setRemoved()
    }

    fun canReceiveWave(gameTime: Long): Boolean {
        if (executionState != null) return false
        val host = this as? ImpetusBranchHost
        if (host != null && (host.abwBranches().isNotEmpty() || host.abwMergeBuffers().isNotEmpty())) {
            return false
        }
        // 首帧未收波或 /time 回退时直接放行，避免负数差值/溢出误判冷却
        return lastReceiveTick < 0L || gameTime < lastReceiveTick || gameTime - lastReceiveTick >= COOLDOWN_TICKS
    }

    /**
     * 接收到无线媒质波后按标准促动石流程校验闭合回自身并起振，
     * 校验通过后将发信端的栈与 `userData` 注入首帧镜像。
     */
    fun receiveWirelessWave(incomingImage: CastingImage, caster: ServerPlayer?): Boolean {
        val srv = level as? ServerLevel ?: return false
        if (!canReceiveWave(srv.gameTime)) return false
        lastReceiveTick = srv.gameTime
        super.startExecution(caster)
        val state = this.executionState ?: return false
        state.currentImage = AmbitReshapeHook.clearShape(incomingImage).withOverriddenUsedOps(0)
        setChanged()
        return true
    }

    override fun applyScryingLensOverlay(
        lines: MutableList<Pair<ItemStack, Component>>,
        state: BlockState,
        pos: BlockPos,
        observer: Player,
        world: Level,
        hitFace: Direction,
    ) {
        super.applyScryingLensOverlay(lines, state, pos, observer, world, hitFace)
        channelState.appendLensLines(lines)
    }

    override fun saveModData(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        super.saveModData(tag, registries)
        channelState.save(tag)
    }

    override fun loadModData(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        super.loadModData(tag, registries)
        channelState.load(tag)
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                WirelessWaveNetwork.registerReceiver(srv, worldPosition, channelState.key())
            }
        }
    }

    // ==================== ADIotaHolder ====================

    override fun readIota(): at.petrak.hexcasting.api.casting.iota.Iota = channelState.readIota(level?.registryAccess())

    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean = channelState.writeIota(iota, simulate, level?.registryAccess())

    override fun writeable(): Boolean = true

    companion object {
        private const val COOLDOWN_TICKS = 4L
    }
}
