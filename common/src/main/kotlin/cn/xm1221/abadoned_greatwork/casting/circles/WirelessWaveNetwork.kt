package cn.xm1221.abadoned_greatwork.casting.circles

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.api.iotaFromNbt
import cn.xm1221.abadoned_greatwork.api.iotaToNbt
import net.minecraft.core.HolderLookup
import cn.xm1221.abadoned_greatwork.block.entity.WaveReceiverBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.WaveRepeaterBlockEntity
import com.mojang.datafixers.util.Pair
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 无线频率键：默认按整数频道 `0..15` 匹配；若玩家写入了自定义 Iota，则按精确 NBT 匹配。
 */
sealed interface WirelessChannelKey {
    data class Numeric(val channel: Int) : WirelessChannelKey
    data class CustomIota(val tag: CompoundTag) : WirelessChannelKey
}

/**
 * 发射件与接收件共用的频道/Iota 调谐状态，顺便承载双端统一的透镜提示与落盘逻辑。
 */
class WirelessChannelState(
    private val onDirty: () -> Unit,
) {
    var channel: Int = 0
        private set

    var customIotaTag: CompoundTag? = null
        private set

    fun key(): WirelessChannelKey =
        customIotaTag?.let { WirelessChannelKey.CustomIota(it.copy()) }
            ?: WirelessChannelKey.Numeric(channel)

    /** 切换整数频道 `0..15`；若先前写入过自定义 Iota，则切回整数频道模式。 */
    fun cycleChannel(delta: Int): Int {
        customIotaTag = null
        channel = Math.floorMod(channel + delta, CHANNEL_COUNT)
        onDirty()
        return channel
    }

    fun readIota(registries: HolderLookup.Provider?): Iota =
        customIotaTag?.let { iotaFromNbt(it, registries) } ?: DoubleIota(channel.toDouble())

    fun writeIota(iota: Iota?, simulate: Boolean, registries: HolderLookup.Provider?): Boolean {
        if (simulate) return true
        if (iota == null) {
            customIotaTag = null
            channel = 0
        } else if (iota is DoubleIota && isIntegerChannel(iota.double)) {
            customIotaTag = null
            channel = iota.double.roundToInt()
        } else {
            customIotaTag = iotaToNbt(iota, registries)
        }
        onDirty()
        return true
    }

    private fun isIntegerChannel(value: Double): Boolean {
        if (!value.isFinite()) return false
        val rounded = value.roundToInt()
        return rounded in 0 until CHANNEL_COUNT && abs(value - rounded) < 1e-6
    }

    fun save(tag: CompoundTag) {
        tag.putInt(TAG_CHANNEL, channel)
        customIotaTag?.let { tag.put(TAG_CUSTOM_IOTA, it.copy()) }
    }

    fun copyTo(target: CompoundTag) {
        target.remove(TAG_CHANNEL)
        target.remove(TAG_CUSTOM_IOTA)
        save(target)
    }

    fun load(tag: CompoundTag) {
        channel = if (tag.contains(TAG_CHANNEL)) Math.floorMod(tag.getInt(TAG_CHANNEL), CHANNEL_COUNT) else 0
        customIotaTag = if (tag.contains(TAG_CUSTOM_IOTA, Tag.TAG_COMPOUND.toInt())) {
            tag.getCompound(TAG_CUSTOM_IOTA).copy()
        } else {
            null
        }
    }

    fun channelDisplay(registries: HolderLookup.Provider? = null): Component =
        customIotaTag?.let { iotaFromNbt(it, registries)?.display() }
            ?: Component.literal(channel.toString()).withStyle(ChatFormatting.LIGHT_PURPLE)

    fun appendLensLines(lines: MutableList<Pair<ItemStack, Component>>) {
        lines.add(
            Pair(
                ItemStack(Items.AMETHYST_SHARD),
                Component.translatable("text.abadoned_greatwork.wireless.channel", channelDisplay()),
            )
        )
    }

    companion object {
        const val CHANNEL_COUNT = 16
        private const val TAG_CHANNEL = "wireless_channel"
        private const val TAG_CUSTOM_IOTA = "wireless_iota"
    }
}

data class TransmitResult(
    val triggeredCount: Int,
    val maxRange: Double,
    val slatesPassed: Long,
    val relayedCount: Int = 0,
)

/**
 * 无线媒质波调度网络。
 *
 * 物理模型（天线 + 励磁线圈 + 偶极辐射 + 号角中继）：
 * 1. **同频谐振与分桶索引**：按频道建索引，发射端与中继器只检索同频目标；先做整型坐标距离平方裁剪，
 *    绝不惊动射程外或其它频道的区块实体。
 * 2. **石板步数决定射程**：有效最大半径随激活该发信石的媒质波已走过的组件步数（`reachedSlate`，允许重复经过导向石回环叠加）
 *    线性增长至 [MAX_RANGE]，且无线发射本身**不再额外消耗媒质**。
 * 3. **偶极子方向图**：垂直发射天线轴线的赤道面辐射最强（`sin²θ = 1`），沿天线轴线上下存在盲区
 *    （`sin²θ < MIN_RADIATION_FACTOR` 时不耦合）；号角中继器在其开口前向半球获得额外 [HORN_FORWARD_RANGE_MULT] 增益。
 * 4. **多跳防回环**：每次波携带已访问节点集合与剩余跳数 [MAX_REPEATER_HOPS]，并在下一拍延迟中继，杜绝同频乒乓死循环。
 */
object WirelessWaveNetwork {
    const val BASE_RANGE: Double = 8.0
    const val RANGE_PER_SLATE: Double = 6.0
    const val MAX_RANGE: Double = 256.0
    const val MAX_REPEATER_HOPS: Int = 8
    const val REPEATER_MIN_SLATES: Long = 5L
    const val REPEATER_SLATE_BONUS: Long = 4L
    private const val HORN_FORWARD_RANGE_MULT: Double = 1.25
    private const val MIN_RADIATION_FACTOR: Double = 0.15
    private const val MIN_REPEATER_RADIATION_FACTOR: Double = 0.08
    private const val MAX_PARTICLE_LINKS_PER_PULSE: Int = 4
    private const val PARTICLE_COOLDOWN_TICKS: Long = 4L

    private class ChannelBuckets {
        val channelByPos = HashMap<BlockPos, WirelessChannelKey>()
        val posByChannel = HashMap<WirelessChannelKey, HashSet<BlockPos>>()

        fun put(pos: BlockPos, key: WirelessChannelKey) {
            val immutable = pos.immutable()
            val prev = channelByPos.put(immutable, key)
            if (prev == key) {
                posByChannel.computeIfAbsent(key) { HashSet() }.add(immutable)
                return
            }
            if (prev != null) {
                val oldBucket = posByChannel[prev]
                if (oldBucket != null) {
                    oldBucket.remove(immutable)
                    if (oldBucket.isEmpty()) posByChannel.remove(prev)
                }
            }
            posByChannel.computeIfAbsent(key) { HashSet() }.add(immutable)
        }

        fun remove(pos: BlockPos) {
            val prev = channelByPos.remove(pos) ?: return
            val bucket = posByChannel[prev] ?: return
            bucket.remove(pos)
            if (bucket.isEmpty()) posByChannel.remove(prev)
        }

        fun bucket(key: WirelessChannelKey): HashSet<BlockPos>? = posByChannel[key]

        fun isEmpty(): Boolean = channelByPos.isEmpty()
    }

    private class LevelIndex {
        val receivers = ChannelBuckets()
        val repeaters = ChannelBuckets()
        val lastParticleTickByTx = HashMap<BlockPos, Long>()

        fun isEmpty(): Boolean = receivers.isEmpty() && repeaters.isEmpty()
    }

    private val indexByLevel = WeakHashMap<ServerLevel, LevelIndex>()

    fun registerReceiver(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey) {
        indexByLevel.computeIfAbsent(level) { LevelIndex() }.receivers.put(pos, key)
    }

    fun updateReceiverChannel(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey) {
        indexByLevel.computeIfAbsent(level) { LevelIndex() }.receivers.put(pos, key)
    }

    fun unregisterReceiver(level: ServerLevel, pos: BlockPos) {
        val index = indexByLevel[level] ?: return
        index.receivers.remove(pos)
        index.lastParticleTickByTx.remove(pos)
        if (index.isEmpty()) indexByLevel.remove(level)
    }

    fun registerRepeater(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey) {
        indexByLevel.computeIfAbsent(level) { LevelIndex() }.repeaters.put(pos, key)
    }

    fun updateRepeaterChannel(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey) {
        indexByLevel.computeIfAbsent(level) { LevelIndex() }.repeaters.put(pos, key)
    }

    fun unregisterRepeater(level: ServerLevel, pos: BlockPos) {
        val index = indexByLevel[level] ?: return
        index.repeaters.remove(pos)
        index.lastParticleTickByTx.remove(pos)
        if (index.isEmpty()) indexByLevel.remove(level)
    }

    fun computeRangeForSlates(slatesPassed: Long): Double {
        val steps = slatesPassed.coerceAtLeast(1L)
        return (BASE_RANGE + RANGE_PER_SLATE * (steps - 1L)).coerceAtMost(MAX_RANGE)
    }

    private fun effectiveRangeTo(
        delta: Vec3,
        dist: Double,
        mastVec: Vec3,
        hornVec: Vec3?,
        maxRange: Double,
        minRadiationFactor: Double,
    ): Double? {
        val unitDelta = delta.scale(1.0 / dist)
        val cosTheta = abs(unitDelta.dot(mastVec))
        val sin2Theta = (1.0 - cosTheta * cosTheta).coerceAtLeast(0.0)
        if (sin2Theta < minRadiationFactor) return null

        var range = maxRange * sqrt(sin2Theta)
        if (hornVec != null && unitDelta.dot(hornVec) > 0.0) {
            range = (range * HORN_FORWARD_RANGE_MULT).coerceAtMost(MAX_RANGE)
        }
        return range
    }

    /**
     * 发射一次无线媒质波，不消耗媒质；影响半径由 `env.circleState().reachedSlate`（含重复经过）决定。
     */
    fun emitWave(
        level: ServerLevel,
        txPos: BlockPos,
        txState: BlockState,
        txChannel: WirelessChannelKey,
        image: CastingImage,
        env: CircleCastEnv,
    ): TransmitResult {
        val slatesPassed = env.circleState().reachedSlate.coerceAtLeast(1L)
        val caster = env.castingEntity as? ServerPlayer
        val antennaAxis = txState.getValue(BlockStateProperties.FACING)
        return emitWaveInternal(
            level = level,
            txPos = txPos,
            antennaAxisDir = antennaAxis,
            hornForwardDir = null,
            txChannel = txChannel,
            image = image,
            caster = caster,
            slatesPassed = slatesPassed,
            hopsLeft = MAX_REPEATER_HOPS,
            visitedNodes = emptySet(),
            playBeaconSound = true,
        )
    }

    /**
     * 从中继器（无线接力或入环触发）广播一次无线媒质波。
     */
    fun emitFromRepeater(
        level: ServerLevel,
        txPos: BlockPos,
        antennaAxisDir: Direction,
        hornForwardDir: Direction,
        txChannel: WirelessChannelKey,
        image: CastingImage,
        caster: ServerPlayer?,
        slatesPassed: Long,
        hopsLeft: Int,
        visitedNodes: Set<BlockPos>,
    ): TransmitResult {
        val boostedSlates = maxOf(slatesPassed + REPEATER_SLATE_BONUS, REPEATER_MIN_SLATES)
        return emitWaveInternal(
            level = level,
            txPos = txPos,
            antennaAxisDir = antennaAxisDir,
            hornForwardDir = hornForwardDir,
            txChannel = txChannel,
            image = image,
            caster = caster,
            slatesPassed = boostedSlates,
            hopsLeft = hopsLeft,
            visitedNodes = visitedNodes,
            playBeaconSound = false,
        )
    }

    private fun emitWaveInternal(
        level: ServerLevel,
        txPos: BlockPos,
        antennaAxisDir: Direction,
        hornForwardDir: Direction?,
        txChannel: WirelessChannelKey,
        image: CastingImage,
        caster: ServerPlayer?,
        slatesPassed: Long,
        hopsLeft: Int,
        visitedNodes: Set<BlockPos>,
        playBeaconSound: Boolean,
    ): TransmitResult {
        val maxRange = computeRangeForSlates(slatesPassed)
        val index = indexByLevel[level] ?: return TransmitResult(0, maxRange, slatesPassed, 0)
        val rxBucket = index.receivers.bucket(txChannel)
        val repBucket = if (hopsLeft > 0) index.repeaters.bucket(txChannel) else null
        if ((rxBucket == null || rxBucket.isEmpty()) && (repBucket == null || repBucket.isEmpty())) {
            return TransmitResult(0, maxRange, slatesPassed, 0)
        }

        val txCenter = Vec3.atCenterOf(txPos)
        val mastVec = Vec3.atLowerCornerOf(antennaAxisDir.normal)
        val hornVec = hornForwardDir?.let { Vec3.atLowerCornerOf(it.normal) }

        // 先用可能包含号角前向增益的上界+容差做整型 distSqr 裁剪，避免惊动射程外区块实体
        val upperRange = if (hornVec != null) (maxRange * HORN_FORWARD_RANGE_MULT).coerceAtMost(MAX_RANGE) else maxRange
        val maxRangeSqCeil = ceil((upperRange + 1.5) * (upperRange + 1.5))

        val now = level.gameTime
        val lastFx = index.lastParticleTickByTx[txPos]
        val allowParticles = lastFx == null || now < lastFx || now - lastFx >= PARTICLE_COOLDOWN_TICKS
        var particleLinksLeft = if (allowParticles) MAX_PARTICLE_LINKS_PER_PULSE else 0

        var staleReceivers: ArrayList<BlockPos>? = null
        var staleRepeaters: ArrayList<BlockPos>? = null
        var triggered = 0
        var relayed = 0

        if (rxBucket != null && rxBucket.isNotEmpty()) {
            for (rxPos in rxBucket.toTypedArray()) {
                if (rxPos == txPos) continue
                if (rxPos.distSqr(txPos) > maxRangeSqCeil) continue
                if (!level.isLoaded(rxPos)) continue

                val rxBe = level.getBlockEntity(rxPos) as? WaveReceiverBlockEntity
                if (rxBe == null || rxBe.isRemoved) {
                    (staleReceivers ?: ArrayList<BlockPos>().also { staleReceivers = it }).add(rxPos)
                    continue
                }
                if (rxBe.channelState.key() != txChannel) {
                    index.receivers.put(rxPos, rxBe.channelState.key())
                    continue
                }
                if (!rxBe.canReceiveWave(now)) continue

                val rxCenter = Vec3.atCenterOf(rxPos)
                val delta = rxCenter.subtract(txCenter)
                val dist = delta.length()
                if (dist < 1e-4) continue

                val effectiveRange = effectiveRangeTo(delta, dist, mastVec, hornVec, maxRange, MIN_RADIATION_FACTOR) ?: continue
                if (dist > effectiveRange) continue

                if (rxBe.receiveWirelessWave(image, caster)) {
                    if (particleLinksLeft > 0) {
                        val rxFacing = rxBe.blockState.getValue(BlockStateProperties.FACING)
                        spawnPropagationEffects(level, txCenter, mastVec, rxCenter, rxFacing)
                        particleLinksLeft--
                    }
                    triggered++
                }
            }
        }

        if (repBucket != null && repBucket.isNotEmpty() && hopsLeft > 0) {
            val nextVisited = HashSet<BlockPos>(visitedNodes.size + 1).apply {
                addAll(visitedNodes)
                add(txPos.immutable())
            }
            for (repPos in repBucket.toTypedArray()) {
                if (repPos == txPos || repPos in nextVisited) continue
                if (repPos.distSqr(txPos) > maxRangeSqCeil) continue
                if (!level.isLoaded(repPos)) continue

                val repBe = level.getBlockEntity(repPos) as? WaveRepeaterBlockEntity
                if (repBe == null || repBe.isRemoved) {
                    (staleRepeaters ?: ArrayList<BlockPos>().also { staleRepeaters = it }).add(repPos)
                    continue
                }
                if (repBe.channelState.key() != txChannel) {
                    index.repeaters.put(repPos, repBe.channelState.key())
                    continue
                }
                if (!repBe.canRelayWave(now)) continue

                val repCenter = Vec3.atCenterOf(repPos)
                val delta = repCenter.subtract(txCenter)
                val dist = delta.length()
                if (dist < 1e-4) continue

                val effectiveRange = effectiveRangeTo(delta, dist, mastVec, hornVec, maxRange, MIN_REPEATER_RADIATION_FACTOR) ?: continue
                if (dist > effectiveRange) continue

                if (repBe.scheduleRelay(image, caster, slatesPassed, hopsLeft - 1, nextVisited, now)) {
                    if (particleLinksLeft > 0) {
                        val repFacing = repBe.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        spawnPropagationEffects(level, txCenter, mastVec, repCenter, repFacing)
                        particleLinksLeft--
                    }
                    relayed++
                }
            }
        }

        staleReceivers?.forEach { index.receivers.remove(it) }
        staleRepeaters?.forEach { index.repeaters.remove(it) }
        if ((triggered > 0 || relayed > 0) && allowParticles) {
            index.lastParticleTickByTx[txPos.immutable()] = now
            if (playBeaconSound) {
                level.playSound(
                    null,
                    txPos,
                    SoundEvents.BEACON_ACTIVATE,
                    SoundSource.BLOCKS,
                    0.45f,
                    1.35f,
                )
            }
        }
        return TransmitResult(triggered, maxRange, slatesPassed, relayed)
    }

    private fun spawnPropagationEffects(
        level: ServerLevel,
        txCenter: Vec3,
        txAxisVec: Vec3,
        rxCenter: Vec3,
        rxFacing: Direction,
    ) {
        val txTip = txCenter.add(txAxisVec.scale(0.5))
        val rxTip = rxCenter.add(Vec3.atLowerCornerOf(rxFacing.normal).scale(0.45))
        level.sendParticles(ParticleTypes.END_ROD, txTip.x, txTip.y, txTip.z, 4, 0.08, 0.08, 0.08, 0.03)
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, rxTip.x, rxTip.y, rxTip.z, 6, 0.14, 0.14, 0.14, 0.04)

        val steps = (txTip.distanceTo(rxTip) / 5.0).roundToInt().coerceIn(2, 6)
        for (i in 1 until steps) {
            val t = i.toDouble() / steps
            val p = txTip.lerp(rxTip, t)
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0)
        }
    }

    /**
     * 先按 X 再按 Y 顺时针（与原版方块状态 `"x"` / `"y"` 旋转完全一致）把默认状态下的盒边界转到目标碰撞盒。
     */
    fun buildOrientedBoxesXY(
        rotXDeg: Int,
        rotYDeg: Int,
        vararg baseBoxes: DoubleArray,
    ): VoxelShape {
        var combined = Block.box(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        for (b in baseBoxes) {
            val r = rotateBoxXY(rotXDeg, rotYDeg, b[0], b[1], b[2], b[3], b[4], b[5])
            combined = net.minecraft.world.phys.shapes.Shapes.or(
                combined,
                Block.box(r[0], r[1], r[2], r[3], r[4], r[5]),
            )
        }
        return combined
    }

    private fun rotateBoxXY(
        rotXDeg: Int,
        rotYDeg: Int,
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
    ): DoubleArray {
        val sx = Math.floorMod(rotXDeg, 360) / 90
        val sy = Math.floorMod(rotYDeg, 360) / 90
        fun transform(x0: Double, y0: Double, z0: Double): Triple<Double, Double, Double> {
            var x = x0
            var y = y0
            var z = z0
            repeat(sx) {
                // Rx(-90): (x, y, z) -> (x, z, 16 - y)
                val ny = z
                val nz = 16.0 - y
                y = ny
                z = nz
            }
            repeat(sy) {
                // Ry(-90): (x, y, z) -> (16 - z, y, x)
                val nx = 16.0 - z
                val nz = x
                x = nx
                z = nz
            }
            return Triple(x, y, z)
        }
        val a = transform(x1, y1, z1)
        val b = transform(x2, y2, z2)
        return doubleArrayOf(
            minOf(a.first, b.first),
            minOf(a.second, b.second),
            minOf(a.third, b.third),
            maxOf(a.first, b.first),
            maxOf(a.second, b.second),
            maxOf(a.third, b.third),
        )
    }

    /**
     * 把默认朝上（`+Y`）的模型盒边界旋转到指定六向 `facing`（对应 `"x": 0/90/180` + `"y": 0/90/180/270`）。
     */
    fun buildOrientedBoxes(
        facing: Direction,
        vararg upBoxes: DoubleArray,
    ): VoxelShape {
        val (rx, ry) = when (facing) {
            Direction.UP -> 0 to 0
            Direction.DOWN -> 180 to 0
            Direction.NORTH -> 90 to 0
            Direction.EAST -> 90 to 90
            Direction.SOUTH -> 90 to 180
            Direction.WEST -> 90 to 270
        }
        return buildOrientedBoxesXY(rx, ry, *upBoxes)
    }
}
