package cn.xm1221.abadoned_greatwork.casting.circles

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.common.lib.HexAttributes
import cn.xm1221.abadoned_greatwork.api.AbadonedImageComponents
import cn.xm1221.abadoned_greatwork.api.circleMeta
import cn.xm1221.abadoned_greatwork.api.withCircleMeta
import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import cn.xm1221.abadoned_greatwork.networking.msg.MsgSyncAmbitLinesS2C
import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.roundToLong

/**
 * 重塑后的“柳叶笔”多边形立柱施法域：
 * - 水平面（XZ）使用 **奇偶规则（Even-Odd）** 闭合顶点连线，支持任意凹角且允许交叉而不崩溃；
 * - 垂直方向（Y）取同频锚点最低与最高格的包络 `[minY, maxY]`（高度 `H = maxY - minY + 1`）；
 * - 体积按与 MC 格点一致的体素数计算：`V = 水平命中格数 S × 高度 H`。
 */
class AmbitPolygonShape(
    val minY: Int,
    val maxY: Int,
    val xs: DoubleArray,
    val zs: DoubleArray,
    val areaBlocks: Long,
    val volumeBlocks: Long,
) {
    val height: Int
        get() = (maxY - minY + 1).coerceAtLeast(1)

    fun contains(vec: Vec3): Boolean {
        val iy = Mth.floor(vec.y)
        if (iy < minY || iy > maxY) return false
        val ix = Mth.floor(vec.x)
        val iz = Mth.floor(vec.z)
        return isBlockInsideOrOnBoundary(ix, iz, xs, zs)
    }

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()
        tag.putInt(TAG_MIN_Y, minY)
        tag.putInt(TAG_MAX_Y, maxY)
        tag.putLong(TAG_AREA, areaBlocks)
        tag.putLong(TAG_VOLUME, volumeBlocks)
        val n = xs.size
        val intXs = IntArray(n) { Mth.floor(xs[it]) }
        val intZs = IntArray(n) { Mth.floor(zs[it]) }
        tag.putIntArray(TAG_XS, intXs)
        tag.putIntArray(TAG_ZS, intZs)
        return tag
    }

    sealed interface ValidationResult {
        data object Empty : ValidationResult
        data class TooFewAnchors(val count: Int) : ValidationResult
        data class OverBudget(
            val neededVolume: Long,
            val budgetVolume: Long,
            val areaBlocks: Long,
            val height: Int,
        ) : ValidationResult

        data class Valid(val shape: AmbitPolygonShape) : ValidationResult
    }

    companion object {
        private const val SCAN_EPSILON = 1.0e-4
        private const val MAX_SCANLINE_SPAN = 2048
        private const val TAG_MIN_Y = "min_y"
        private const val TAG_MAX_Y = "max_y"
        private const val TAG_XS = "xs"
        private const val TAG_ZS = "zs"
        private const val TAG_AREA = "area"
        private const val TAG_VOLUME = "volume"

        fun fromNbt(tag: CompoundTag): AmbitPolygonShape? {
            if (!tag.contains(TAG_XS, Tag.TAG_INT_ARRAY.toInt())) return null
            val intXs = tag.getIntArray(TAG_XS)
            val intZs = tag.getIntArray(TAG_ZS)
            if (intXs.size < 3 || intXs.size != intZs.size) return null
            val minY = tag.getInt(TAG_MIN_Y)
            val maxY = max(minY, tag.getInt(TAG_MAX_Y))
            val xs = DoubleArray(intXs.size) { intXs[it] + 0.5 }
            val zs = DoubleArray(intZs.size) { intZs[it] + 0.5 }
            val area = tag.getLong(TAG_AREA).coerceAtLeast(0L)
            val volume = tag.getLong(TAG_VOLUME).coerceAtLeast(0L)
            return AmbitPolygonShape(minY, maxY, xs, zs, area, volume)
        }

        /**
         * 判断整数格 `(ix, iz)` 的方块中心是否在封闭多边形内部或边界线上：
         * 先判点是否落于任意一条边界线段（含端点），再走与扫描线一致的奇偶射线测试，
         * 确保东/南外沿及所有道引标自身角点不会因半开区间被意外裁掉。
         */
        fun isBlockInsideOrOnBoundary(ix: Int, iz: Int, xs: DoubleArray, zs: DoubleArray): Boolean {
            val n = xs.size
            if (n < 3) return false
            val px = ix + 0.5
            val pz = iz + 0.5
            var j = n - 1
            for (i in 0 until n) {
                val xi = xs[i]
                val zi = zs[i]
                val xj = xs[j]
                val zj = zs[j]
                if (isPointOnSegment(px, pz, xj, zj, xi, zi)) {
                    return true
                }
                j = i
            }
            return isPointInsideEvenOdd(px, pz + SCAN_EPSILON, xs, zs)
        }

        private fun isPointOnSegment(
            px: Double,
            pz: Double,
            ax: Double,
            az: Double,
            bx: Double,
            bz: Double,
        ): Boolean {
            val minX = min(ax, bx) - SCAN_EPSILON
            val maxX = max(ax, bx) + SCAN_EPSILON
            val minZ = min(az, bz) - SCAN_EPSILON
            val maxZ = max(az, bz) + SCAN_EPSILON
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) return false
            val dx = bx - ax
            val dz = bz - az
            val lenSq = dx * dx + dz * dz
            if (lenSq <= 1.0e-12) return true
            val cross = kotlin.math.abs((px - ax) * dz - (pz - az) * dx)
            return cross * cross <= SCAN_EPSILON * SCAN_EPSILON * lenSq
        }

        /**
         * 与扫描线计数保持严格一致的奇偶射线测试。
         */
        fun isPointInsideEvenOdd(px: Double, pz: Double, xs: DoubleArray, zs: DoubleArray): Boolean {
            val n = xs.size
            if (n < 3) return false
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val xi = xs[i]
                val zi = zs[i]
                val xj = xs[j]
                val zj = zs[j]
                if ((zi > pz) != (zj > pz)) {
                    val xIntersect = xj + (pz - zj) * (xi - xj) / (zi - zj)
                    if (px < xIntersect) {
                        inside = !inside
                    }
                }
                j = i
            }
            return inside
        }

        /**
         * 使用奇偶扫描线 + 边界段并集统计封闭多边形覆盖的整数方块柱数（与 [isBlockInsideOrOnBoundary] 严格一致），
         * 并在超出 [budgetVolume] 时提前中止。
         */
        fun computeAndValidate(anchors: List<BlockPos>, budgetVolume: Long): ValidationResult {
            if (anchors.isEmpty()) return ValidationResult.Empty
            if (anchors.size < 3) return ValidationResult.TooFewAnchors(anchors.size)

            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var minZ = Int.MAX_VALUE
            var maxZ = Int.MIN_VALUE
            val n = anchors.size
            val xs = DoubleArray(n)
            val zs = DoubleArray(n)
            for (i in 0 until n) {
                val p = anchors[i]
                xs[i] = p.x + 0.5
                zs[i] = p.z + 0.5
                minY = min(minY, p.y)
                maxY = max(maxY, p.y)
                minZ = min(minZ, p.z)
                maxZ = max(maxZ, p.z)
            }

            val height = (maxY.toLong() - minY.toLong() + 1L).coerceAtLeast(1L)
            val zSpan = maxZ.toLong() - minZ.toLong() + 1L
            if (zSpan > MAX_SCANLINE_SPAN) {
                val estVol = safeMultiply(zSpan, height)
                return ValidationResult.OverBudget(estVol, budgetVolume, zSpan, height.toInt())
            }

            var area = 0L
            val crossings = DoubleArray(n)
            val rowIntervals = ArrayList<LongRange>(n * 2)
            for (iz in minZ..maxZ) {
                rowIntervals.clear()
                val pzInterior = iz + 0.5 + SCAN_EPSILON
                val pzEdge = iz + 0.5
                var count = 0
                var j = n - 1
                for (i in 0 until n) {
                    val xi = xs[i]
                    val zi = zs[i]
                    val xj = xs[j]
                    val zj = zs[j]

                    // 1. 奇偶内部交点
                    if ((zi > pzInterior) != (zj > pzInterior)) {
                        crossings[count++] = xj + (pzInterior - zj) * (xi - xj) / (zi - zj)
                    }

                    // 2. 落在本行格中心的边界（水平边或正好压线通过格心的斜边/端点）
                    if (kotlin.math.abs(zi - zj) <= SCAN_EPSILON) {
                        if (kotlin.math.abs(zi - pzEdge) <= SCAN_EPSILON) {
                            val minIx = Mth.floor(min(xi, xj)).toLong()
                            val maxIx = Mth.floor(max(xi, xj)).toLong()
                            if (maxIx >= minIx) rowIntervals.add(minIx..maxIx)
                        }
                    } else if (pzEdge >= min(zi, zj) - SCAN_EPSILON && pzEdge <= max(zi, zj) + SCAN_EPSILON) {
                        val t = ((pzEdge - zj) / (zi - zj)).coerceIn(0.0, 1.0)
                        val xAtZ = xj + t * (xi - xj)
                        val nearestIx = floor(xAtZ).toLong()
                        val pxCand = nearestIx + 0.5
                        if (isPointOnSegment(pxCand, pzEdge, xj, zj, xi, zi)) {
                            rowIntervals.add(nearestIx..nearestIx)
                        }
                    }

                    j = i
                }
                if (count >= 2) {
                    crossings.sort(0, count)
                    var k = 0
                    while (k + 1 < count) {
                        val x0 = crossings[k]
                        val x1 = crossings[k + 1]
                        if (x1 > x0) {
                            var startIx = ceil(x0 - 0.5).toLong()
                            while (startIx + 0.5 < x0) startIx++
                            var endIx = floor(x1.nextDown() - 0.5).toLong()
                            while (endIx + 0.5 >= x1) endIx--
                            if (endIx >= startIx) {
                                rowIntervals.add(startIx..endIx)
                            }
                        }
                        k += 2
                    }
                }

                if (rowIntervals.isNotEmpty()) {
                    rowIntervals.sortWith(compareBy<LongRange> { it.first }.thenBy { it.last })
                    var curStart = rowIntervals[0].first
                    var curEnd = rowIntervals[0].last
                    for (idx in 1 until rowIntervals.size) {
                        val next = rowIntervals[idx]
                        if (next.first <= curEnd + 1L) {
                            if (next.last > curEnd) curEnd = next.last
                        } else {
                            area += (curEnd - curStart + 1L)
                            curStart = next.first
                            curEnd = next.last
                        }
                    }
                    area += (curEnd - curStart + 1L)

                    val currentVol = safeMultiply(area, height)
                    if (currentVol > budgetVolume) {
                        return ValidationResult.OverBudget(
                            neededVolume = currentVol,
                            budgetVolume = budgetVolume,
                            areaBlocks = area,
                            height = height.toInt(),
                        )
                    }
                }
            }

            val totalVolume = safeMultiply(area, height)
            if (totalVolume > budgetVolume) {
                return ValidationResult.OverBudget(totalVolume, budgetVolume, area, height.toInt())
            }

            return ValidationResult.Valid(
                AmbitPolygonShape(
                    minY = minY,
                    maxY = maxY,
                    xs = xs,
                    zs = zs,
                    areaBlocks = area,
                    volumeBlocks = totalVolume,
                )
            )
        }

        private fun safeMultiply(a: Long, b: Long): Long {
            if (a <= 0L || b <= 0L) return 0L
            if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE
            return a * b
        }
    }
}

/**
 * 范围锚点网络：按维度和频道分桶管理世界内已加载的 [AmbitAnchorBlockEntity] 坐标与顺位。
 */
object AmbitNetwork {

    private data class AnchorEntry(
        val pos: BlockPos,
        val order: Long,
    ) : Comparable<AnchorEntry> {
        override fun compareTo(other: AnchorEntry): Int {
            val c = order.compareTo(other.order)
            if (c != 0) return c
            return pos.compareTo(other.pos)
        }
    }

    private class WorldBucket {
        val channelByPos = HashMap<BlockPos, WirelessChannelKey>()
        val orderByPos = HashMap<BlockPos, Long>()
        val posByChannel = HashMap<WirelessChannelKey, HashSet<BlockPos>>()

        /**
         * 该坐标当前归属哪个方块实体实例。
         *
         * 用来判断一次注销是否还有效：同坐标换 BE 时，旧实例的 `setRemoved()` 会晚于新实例的
         * 注册执行，若只按坐标删就会把新实例的登记一起抹掉。
         *
         * 这里刻意不调 `Level.getBlockEntity` 去反查——那个方法默认带
         * `EntityCreationType.IMMEDIATE`，在 `setRemoved` 期间反查可能顺手把一个新 BE 建出来。
         */
        val ownerByPos = HashMap<BlockPos, AmbitAnchorBlockEntity>()
        val activeVisualsByImpetus = HashMap<BlockPos, ActiveVisual>()
        val holdersByAnchor = HashMap<BlockPos, HashSet<BlockPos>>()
    }

    private data class ActiveVisual(
        val orderedAnchors: List<BlockPos>,
        val anchorSet: HashSet<BlockPos>,
        val pigmentTag: CompoundTag?,
        val fallbackColor: Int,
    ) {
        fun toPacketLoop(): MsgSyncAmbitLinesS2C.AmbitLineLoop =
            MsgSyncAmbitLinesS2C.AmbitLineLoop(orderedAnchors, pigmentTag?.copy(), fallbackColor)
    }

    private val worlds = WeakHashMap<ServerLevel, WorldBucket>()

    fun init() {
        PlayerEvent.PLAYER_JOIN.register { player ->
            syncToPlayer(player)
        }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ ->
            syncToPlayer(player)
        }
    }

    fun syncToPlayer(player: ServerPlayer) {
        val level = player.serverLevel()
        val wb = worlds[level]
        val loops = wb?.let(::snapshotLoops) ?: emptyList()
        MsgSyncAmbitLinesS2C(level.dimension().location(), loops).sendToPlayer(player)
    }

    private fun snapshotLoops(wb: WorldBucket): List<MsgSyncAmbitLinesS2C.AmbitLineLoop> =
        wb.activeVisualsByImpetus.values
            .filter { it.orderedAnchors.size >= 2 }
            .map { it.toPacketLoop() }

    private fun broadcastActiveLines(level: ServerLevel, wb: WorldBucket) {
        val players = level.players()
        if (players.isEmpty()) return
        MsgSyncAmbitLinesS2C(level.dimension().location(), snapshotLoops(wb)).sendToPlayers(players)
    }

    private fun bucket(level: ServerLevel): WorldBucket = worlds.getOrPut(level) { WorldBucket() }

    fun registerAnchor(
        level: ServerLevel,
        pos: BlockPos,
        key: WirelessChannelKey,
        order: Long,
        owner: AmbitAnchorBlockEntity? = null,
    ) {
        val immutable = pos.immutable()
        val wb = bucket(level)
        wb.channelByPos.remove(immutable)?.let { oldKey ->
            wb.posByChannel[oldKey]?.let { set ->
                set.remove(immutable)
                if (set.isEmpty()) wb.posByChannel.remove(oldKey)
            }
        }
        wb.channelByPos[immutable] = key
        wb.orderByPos[immutable] = order
        wb.posByChannel.getOrPut(key) { HashSet() }.add(immutable)
        if (owner != null) {
            wb.ownerByPos[immutable] = owner
        }
    }

    /**
     * 注销 [pos] 上的锚点。
     *
     * 传入 [owner] 时做归属校验：只有当索引里记的仍是这个实例才真正删除。传 `null` 表示强制删除
     * （给不持有实例的调用方用）。
     */
    fun unregisterAnchor(level: ServerLevel, pos: BlockPos, owner: AmbitAnchorBlockEntity? = null) {
        val wb = worlds[level] ?: return
        val currentOwner = wb.ownerByPos[pos]
        if (owner != null && currentOwner != null && currentOwner !== owner) {
            // 这个坐标已经被另一个（更新的）实例接管了，旧实例的注销必须让路
            return
        }
        wb.ownerByPos.remove(pos)
        wb.orderByPos.remove(pos)
        var visualsChanged = false
        wb.holdersByAnchor.remove(pos)?.forEach { impPos ->
            val oldVisual = wb.activeVisualsByImpetus[impPos]
            if (oldVisual != null) {
                val remaining = oldVisual.orderedAnchors.filter { it != pos }
                if (remaining.size < 2) {
                    wb.activeVisualsByImpetus.remove(impPos)
                } else {
                    wb.activeVisualsByImpetus[impPos] = oldVisual.copy(
                        orderedAnchors = remaining,
                        anchorSet = remaining.toHashSet(),
                    )
                }
                visualsChanged = true
            }
        }
        if (visualsChanged) {
            broadcastActiveLines(level, wb)
        }
        val oldKey = wb.channelByPos.remove(pos) ?: return
        wb.posByChannel[oldKey]?.let { set ->
            set.remove(pos)
            if (set.isEmpty()) wb.posByChannel.remove(oldKey)
        }
    }

    /**
     * 当法术环经过 `AmbitSwitchBlock` 并成功切换为 [anchors] 围出的范围时点亮这批道引标及闭环标定线，
     * 并自动熄灭同一促动石此前持有的其它道引标与标定线。
     */
    fun activateAnchorsForImpetus(
        level: ServerLevel,
        impetusPos: BlockPos,
        anchors: List<BlockPos>,
        pigmentTag: CompoundTag?,
        fallbackColor: Int,
    ) {
        val imp = impetusPos.immutable()
        val wb = bucket(level)
        val ordered = anchors.map { it.immutable() }
        val newSet = ordered.toHashSet()
        val prevVisual = wb.activeVisualsByImpetus[imp]

        if (prevVisual != null) {
            for (oldAnchor in prevVisual.anchorSet) {
                if (oldAnchor !in newSet) {
                    removeHolderAndMaybeExtinguish(level, wb, oldAnchor, imp)
                }
            }
        }

        if (newSet.isEmpty()) {
            if (wb.activeVisualsByImpetus.remove(imp) != null) {
                broadcastActiveLines(level, wb)
            }
            return
        }

        val nextVisual = ActiveVisual(
            orderedAnchors = ordered,
            anchorSet = newSet,
            pigmentTag = pigmentTag?.copy(),
            fallbackColor = fallbackColor,
        )
        wb.activeVisualsByImpetus[imp] = nextVisual
        for (anchorPos in newSet) {
            wb.holdersByAnchor.getOrPut(anchorPos) { HashSet() }.add(imp)
            val be = wb.ownerByPos[anchorPos] ?: (level.getBlockEntity(anchorPos) as? AmbitAnchorBlockEntity)
            be?.setOrbActivation(true, pigmentTag, fallbackColor)
        }

        if (prevVisual != nextVisual) {
            broadcastActiveLines(level, wb)
        }
    }

    /**
     * 促动石结束执行或切换回默认方盒时，熄灭其当前点亮的全部道引标与标定线（若有另一促动石仍在持有则保留）。
     */
    @JvmStatic
    fun deactivateImpetusAnchors(level: ServerLevel, impetusPos: BlockPos) {
        val wb = worlds[level] ?: return
        val held = wb.activeVisualsByImpetus.remove(impetusPos) ?: return
        for (anchorPos in held.anchorSet) {
            removeHolderAndMaybeExtinguish(level, wb, anchorPos, impetusPos)
        }
        broadcastActiveLines(level, wb)
    }

    fun isAnchorHeld(level: ServerLevel, anchorPos: BlockPos): Boolean {
        val wb = worlds[level] ?: return false
        return !wb.holdersByAnchor[anchorPos].isNullOrEmpty()
    }

    private fun removeHolderAndMaybeExtinguish(
        level: ServerLevel,
        wb: WorldBucket,
        anchorPos: BlockPos,
        impetusPos: BlockPos,
    ) {
        val holders = wb.holdersByAnchor[anchorPos]
        if (holders != null) {
            holders.remove(impetusPos)
            if (holders.isEmpty()) {
                wb.holdersByAnchor.remove(anchorPos)
            }
        }
        if (wb.holdersByAnchor[anchorPos].isNullOrEmpty()) {
            val be = wb.ownerByPos[anchorPos] ?: (level.getBlockEntity(anchorPos) as? AmbitAnchorBlockEntity)
            be?.setOrbActivation(false, null, AmbitAnchorBlockEntity.DEFAULT_ORB_COLOR)
        }
    }

    fun updateAnchor(
        level: ServerLevel,
        pos: BlockPos,
        newKey: WirelessChannelKey,
        newOrder: Long,
        owner: AmbitAnchorBlockEntity? = null,
    ) {
        registerAnchor(level, pos, newKey, newOrder, owner)
    }

    /** 频道内道引标连线顺位检查结果。 */
    sealed interface AnchorChainResult {
        data object Empty : AnchorChainResult
        data class TooFewAnchors(val count: Int) : AnchorChainResult
        data class UnassignedOrder(val pos: BlockPos) : AnchorChainResult
        data class DuplicateOrder(val order: Long, val posA: BlockPos, val posB: BlockPos) : AnchorChainResult
        data class Valid(val anchors: List<BlockPos>) : AnchorChainResult
    }

    /** 返回该频道当前已加载的道引标数量。 */
    fun countAnchorsInChannel(level: ServerLevel, key: WirelessChannelKey): Int =
        worlds[level]?.posByChannel?.get(key)?.size ?: 0

    /** 检查同频道是否已有另一根道引标占用了 [order]（用于玩家手调顺位时即时提示重号）。 */
    fun findConflictingPos(
        level: ServerLevel,
        selfPos: BlockPos,
        key: WirelessChannelKey,
        order: Long,
    ): BlockPos? {
        if (order <= 0L) return null
        val wb = worlds[level] ?: return null
        val set = wb.posByChannel[key] ?: return null
        for (p in set) {
            if (p != selfPos && (wb.orderByPos[p] ?: 0L) == order) {
                return p
            }
        }
        return null
    }

    /**
     * 校验并按手动顺位排序该频道的全部已加载道引标：
     * - 必须至少 3 根；
     * - 每一根都必须已手动指定顺位（`order >= 1`）；
     * - 同频道内顺位不允许重复（但允许跳号，例如 `1, 2, 5`）。
     */
    fun resolveChannelAnchors(level: ServerLevel, key: WirelessChannelKey): AnchorChainResult {
        val wb = worlds[level] ?: return AnchorChainResult.Empty
        val set = wb.posByChannel[key] ?: return AnchorChainResult.Empty
        if (set.isEmpty()) return AnchorChainResult.Empty
        if (set.size < 3) return AnchorChainResult.TooFewAnchors(set.size)

        val entries = ArrayList<AnchorEntry>(set.size)
        for (p in set) {
            val ord = wb.orderByPos[p] ?: 0L
            if (ord <= 0L) {
                return AnchorChainResult.UnassignedOrder(p)
            }
            entries.add(AnchorEntry(p, ord))
        }
        entries.sort()
        for (i in 1 until entries.size) {
            if (entries[i - 1].order == entries[i].order) {
                return AnchorChainResult.DuplicateOrder(
                    order = entries[i].order,
                    posA = entries[i - 1].pos,
                    posB = entries[i].pos,
                )
            }
        }
        return AnchorChainResult.Valid(entries.map { it.pos })
    }
}

/**
 * 接入 HexCasting [CastingEnvironment] 范围校验钩子：
 * 当法术波的 `userData` 中携带激活的重塑多边形时，直接以多边形替代法术环原本的方形 `bounds`
 * （仍保留施法者自身贴身与哨卫范围），确保只改变形状、不白嫖总量。
 */
object AmbitReshapeHook {

    const val TAG_ACTIVE_AMBIT = "abw_active_ambit"

    private val COMPONENT_KEY = object : CastingEnvironmentComponent.Key<AmbitScopeComponent> {}

    private class AmbitScopeComponent(
        private val env: CircleCastEnv,
        private val shape: AmbitPolygonShape,
    ) : CastingEnvironmentComponent.IsVecInRange {
        override fun getKey(): CastingEnvironmentComponent.Key<*> = COMPONENT_KEY

        override fun onIsVecInRange(vec: Vec3, current: Boolean): Boolean {
            if (shape.contains(vec)) return true
            return isWithinCasterOrSentinel(env, vec)
        }
    }

    fun init() {
        CastingEnvironment.addCreateEventListener { env, image ->
            if (env !is CircleCastEnv) return@addCreateEventListener
            val shape = readShape(image) ?: return@addCreateEventListener
            env.addExtension(AmbitScopeComponent(env, shape))
        }
    }

    fun circleBudgetVolume(bounds: AABB): Long {
        val dx = bounds.xsize.roundToLong().coerceAtLeast(1L)
        val dy = bounds.ysize.roundToLong().coerceAtLeast(1L)
        val dz = bounds.zsize.roundToLong().coerceAtLeast(1L)
        val xy = if (dx > Long.MAX_VALUE / dy) return Long.MAX_VALUE else dx * dy
        return if (xy > Long.MAX_VALUE / dz) Long.MAX_VALUE else xy * dz
    }

    fun writeShape(image: CastingImage, shape: AmbitPolygonShape): CastingImage {
        val meta = image.circleMeta()
        meta.put(TAG_ACTIVE_AMBIT, shape.toNbt())
        return image.withCircleMeta(meta)
    }

    fun clearShape(image: CastingImage): CastingImage {
        val meta = image.circleMeta()
        if (!meta.contains(TAG_ACTIVE_AMBIT)) return image
        meta.remove(TAG_ACTIVE_AMBIT)
        return image.withCircleMeta(meta)
    }

    fun readShape(image: CastingImage): AmbitPolygonShape? {
        val meta = image.getComponent(AbadonedImageComponents.CIRCLE_META.value)
            ?: return null
        if (!meta.contains(TAG_ACTIVE_AMBIT, Tag.TAG_COMPOUND.toInt())) return null
        return AmbitPolygonShape.fromNbt(meta.getCompound(TAG_ACTIVE_AMBIT))
    }

    private fun isWithinCasterOrSentinel(env: CircleCastEnv, vec: Vec3): Boolean {
        val caster = env.castingEntity as? ServerPlayer ?: return false
        val bb = caster.bbHeight.toDouble()
        if (vec.distanceToSqr(caster.position()) <= bb * bb) return true
        val sentinel = HexAPI.instance().getSentinel(caster) ?: return false
        if (!sentinel.extendsRange() || caster.level().dimension() != sentinel.dimension()) return false
        val radius = caster.getAttributeValue(HexAttributes.SENTINEL_RADIUS)
        return vec.distanceToSqr(sentinel.position()) <= radius * radius + 1.0e-11
    }
}
