package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.block.WaveRepeaterBlock
import cn.xm1221.abadoned_greatwork.casting.circles.TransmitResult
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessChannelState
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessWaveNetwork
import com.mojang.datafixers.util.Pair
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 号角中继器（`wave_repeater`）的方块实体：
 * - 按频道分桶注册至 [WirelessWaveNetwork]，监听并接力同频无线媒质波；
 * - 使用 2 tick 异步调度发波 + 已访问节点集防回环，避免同刻递归与乒乓死循环；
 * - 也可直接串入法术环作为发射节点使用，并支持 `ADIotaHolder` 自定义 Iota 频道。
 */
class WaveRepeaterBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADIotaHolder {

    val channelState: WirelessChannelState = WirelessChannelState { onChannelDirty() }

    private fun onChannelDirty() {
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                WirelessWaveNetwork.updateRepeaterChannel(srv, worldPosition, channelState.key())
            }
        }
        setChanged()
        sync()
    }

    var lastTriggeredCount: Int = 0
        private set

    var lastRelayedCount: Int = 0
        private set

    var lastRangeBlocks: Int = 0
        private set

    @Transient
    private var lastRelayTick: Long = -1L

    @Transient
    private var wirelessGlowUntilTick: Long = -1L

    @Transient
    private var pendingRelay: PendingRelay? = null

    private data class PendingRelay(
        val image: CastingImage,
        val casterUuid: UUID?,
        val slatesPassed: Long,
        val hopsLeft: Int,
        val visitedNodes: Set<BlockPos>,
    )

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (!isRemoved) {
            (level as? ServerLevel)?.let { WirelessWaveNetwork.registerRepeater(it, worldPosition, channelState.key()) }
        }
    }

    override fun clearRemoved() {
        super.clearRemoved()
        val srv = level as? ServerLevel ?: return
        WirelessWaveNetwork.registerRepeater(srv, worldPosition, channelState.key())
    }

    override fun setRemoved() {
        (level as? ServerLevel)?.let { WirelessWaveNetwork.unregisterRepeater(it, worldPosition) }
        super.setRemoved()
    }

    fun canRelayWave(gameTime: Long): Boolean {
        if (pendingRelay != null) return false
        return lastRelayTick < 0L || gameTime < lastRelayTick || gameTime - lastRelayTick >= COOLDOWN_TICKS
    }

    fun scheduleRelay(
        incomingImage: CastingImage,
        caster: ServerPlayer?,
        slatesPassed: Long,
        hopsLeft: Int,
        visitedNodes: Set<BlockPos>,
        gameTime: Long,
    ): Boolean {
        val srv = level as? ServerLevel ?: return false
        if (!canRelayWave(gameTime)) return false

        val snapshot = incomingImage
            .copy(userData = incomingImage.userData.copy())
            .withOverriddenUsedOps(0)
        pendingRelay = PendingRelay(
            image = snapshot,
            casterUuid = caster?.uuid,
            slatesPassed = slatesPassed,
            hopsLeft = hopsLeft,
            visitedNodes = visitedNodes,
        )
        lastRelayTick = gameTime
        wirelessGlowUntilTick = gameTime + RELAY_DELAY_TICKS + ENERGIZED_HOLD_TICKS

        val curState = blockState
        if (!curState.getValue(BlockCircleComponent.ENERGIZED)) {
            srv.setBlockAndUpdate(worldPosition, curState.setValue(BlockCircleComponent.ENERGIZED, true))
        }
        srv.scheduleTick(worldPosition, curState.block, RELAY_DELAY_TICKS)
        return true
    }

    fun clearWirelessGlowTimer() {
        wirelessGlowUntilTick = -1L
    }

    fun onScheduledTick(srv: ServerLevel, state: BlockState) {
        val pending = pendingRelay
        if (pending != null) {
            pendingRelay = null
            val caster = pending.casterUuid?.let { srv.getPlayerByUUID(it) as? ServerPlayer }
            val attachNormal = WaveRepeaterBlock.attachNormal(state)
            val hornDir = state.getValue(WaveRepeaterBlock.FACING)
            val result = WirelessWaveNetwork.emitFromRepeater(
                level = srv,
                txPos = worldPosition,
                antennaAxisDir = attachNormal,
                hornForwardDir = hornDir,
                txChannel = channelState.key(),
                image = pending.image,
                caster = caster,
                slatesPassed = pending.slatesPassed,
                hopsLeft = pending.hopsLeft,
                visitedNodes = pending.visitedNodes,
            )
            recordRelayResult(result)
            spawnHornEffects(srv, hornDir, result.triggeredCount > 0 || result.relayedCount > 0)

            val now = srv.gameTime
            wirelessGlowUntilTick = now + ENERGIZED_HOLD_TICKS
            srv.scheduleTick(worldPosition, state.block, ENERGIZED_HOLD_TICKS)
            return
        }

        val until = wirelessGlowUntilTick
        if (until >= 0L && state.getValue(BlockCircleComponent.ENERGIZED)) {
            val now = srv.gameTime
            if (now >= until) {
                wirelessGlowUntilTick = -1L
                srv.setBlockAndUpdate(worldPosition, state.setValue(BlockCircleComponent.ENERGIZED, false))
            } else {
                srv.scheduleTick(worldPosition, state.block, (until - now).toInt().coerceAtLeast(1))
            }
        }
    }

    private fun spawnHornEffects(level: ServerLevel, hornDir: net.minecraft.core.Direction, coupledAny: Boolean) {
        val center = Vec3.atCenterOf(worldPosition)
        val forward = Vec3.atLowerCornerOf(hornDir.normal)
        val bellPos = center.add(forward.scale(0.55))
        level.sendParticles(ParticleTypes.END_ROD, bellPos.x, bellPos.y, bellPos.z, 5, 0.10, 0.10, 0.10, 0.04)
        level.sendParticles(
            ParticleTypes.ELECTRIC_SPARK,
            bellPos.x + forward.x * 0.25,
            bellPos.y + forward.y * 0.25,
            bellPos.z + forward.z * 0.25,
            if (coupledAny) 8 else 4,
            0.14,
            0.14,
            0.14,
            0.05,
        )
        level.playSound(
            null,
            worldPosition,
            SoundEvents.BELL_RESONATE,
            SoundSource.BLOCKS,
            0.38f,
            1.15f + (channelState.channel % 4) * 0.08f,
        )
    }

    fun recordRelayResult(result: TransmitResult) {
        val roundedRange = result.maxRange.roundToInt()
        if (
            lastTriggeredCount == result.triggeredCount &&
            lastRelayedCount == result.relayedCount &&
            lastRangeBlocks == roundedRange
        ) {
            return
        }
        lastTriggeredCount = result.triggeredCount
        lastRelayedCount = result.relayedCount
        lastRangeBlocks = roundedRange
        setChanged()
        sync()
    }

    override fun saveModData(tag: CompoundTag) {
        channelState.save(tag)
        tag.putInt(TAG_LAST_COUNT, lastTriggeredCount)
        tag.putInt(TAG_LAST_RELAYED, lastRelayedCount)
        tag.putInt(TAG_LAST_RANGE, lastRangeBlocks)
    }

    override fun loadModData(tag: CompoundTag) {
        channelState.load(tag)
        lastTriggeredCount = tag.getInt(TAG_LAST_COUNT)
        lastRelayedCount = tag.getInt(TAG_LAST_RELAYED)
        lastRangeBlocks = tag.getInt(TAG_LAST_RANGE)
        (level as? ServerLevel)?.let { srv ->
            if (!isRemoved) {
                WirelessWaveNetwork.registerRepeater(srv, worldPosition, channelState.key())
            }
        }
    }

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>) {
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
        lines.add(
            Pair(
                ItemStack(Items.GOAT_HORN),
                Component.translatable("text.abadoned_greatwork.wireless.last_relayed", lastRelayedCount),
            )
        )
    }

    // ==================== ADIotaHolder ====================

    override fun readIotaTag(): CompoundTag = channelState.readIotaTag()

    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean = channelState.writeIota(iota, simulate)

    override fun writeable(): Boolean = true

    companion object {
        private const val COOLDOWN_TICKS = 4L
        private const val RELAY_DELAY_TICKS = 2
        private const val ENERGIZED_HOLD_TICKS = 6
        private const val TAG_LAST_COUNT = "last_triggered_count"
        private const val TAG_LAST_RELAYED = "last_relayed_count"
        private const val TAG_LAST_RANGE = "last_range_blocks"
    }
}
