package cn.xm1221.abadoned_greatwork.casting.circles

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.CastingEnvironmentComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.common.lib.HexAttributes
import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
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
        val px = Mth.floor(vec.x) + 0.5
        val pz = Mth.floor(vec.z) + 0.5 + SCAN_EPSILON
        return isPointInsideEvenOdd(px, pz, xs, zs)
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
         * 使用奇偶扫描线统计封闭多边形覆盖的整数方块柱数，并在超出 [budgetVolume] 时提前中止。
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
            for (iz in minZ..maxZ) {
                val pz = iz + 0.5 + SCAN_EPSILON
                var count = 0
                var j = n - 1
                for (i in 0 until n) {
                    val zi = zs[i]
                    val zj = zs[j]
                    if ((zi > pz) != (zj > pz)) {
                        crossings[count++] = xs[j] + (pz - zj) * (xs[i] - xs[j]) / (zi - zj)
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
                                area += (endIx - startIx + 1L)
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
                        k += 2
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
    }

    private val worlds = WeakHashMap<ServerLevel, WorldBucket>()

    private fun bucket(level: ServerLevel): WorldBucket = worlds.getOrPut(level) { WorldBucket() }

    fun registerAnchor(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey, order: Long) {
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
    }

    fun unregisterAnchor(level: ServerLevel, pos: BlockPos) {
        val wb = worlds[level] ?: return
        wb.orderByPos.remove(pos)
        val oldKey = wb.channelByPos.remove(pos) ?: return
        wb.posByChannel[oldKey]?.let { set ->
            set.remove(pos)
            if (set.isEmpty()) wb.posByChannel.remove(oldKey)
        }
    }

    fun updateAnchor(level: ServerLevel, pos: BlockPos, newKey: WirelessChannelKey, newOrder: Long) {
        registerAnchor(level, pos, newKey, newOrder)
    }

    /** 为刚放入某频道的新锚点分配末尾顺位号。 */
    fun allocateAppendOrder(level: ServerLevel, key: WirelessChannelKey): Long {
        val wb = bucket(level)
        val set = wb.posByChannel[key] ?: return 1L
        var maxOrder = 0L
        for (p in set) {
            val o = wb.orderByPos[p] ?: 0L
            if (o > maxOrder) maxOrder = o
        }
        return maxOrder + 1L
    }

    /** 返回该频道当前所有已加载且按顺位排序的锚点坐标列表。 */
    fun getOrderedAnchors(level: ServerLevel, key: WirelessChannelKey): List<BlockPos> {
        val wb = worlds[level] ?: return emptyList()
        val set = wb.posByChannel[key] ?: return emptyList()
        return set.map { p -> AnchorEntry(p, wb.orderByPos[p] ?: 0L) }
            .sorted()
            .map { it.pos }
    }

    /** 返回指定锚点在当前频道中的 `1`-based 排位与总数 `(rank, total)`。 */
    fun getAnchorRank(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey): Pair<Int, Int> {
        val list = getOrderedAnchors(level, key)
        if (list.isEmpty()) return 1 to 1
        val idx = list.indexOf(pos)
        return (if (idx >= 0) idx + 1 else 1) to list.size
    }

    /**
     * 将 [pos] 在该频道中的顺序向后移动一位（末尾回绕至首位），重新规整所有同频锚点顺位并落盘同步。
     *
     * 返回调整后的 `(新1-based排位, 总数)`。
     */
    fun rotateAnchorOrder(level: ServerLevel, pos: BlockPos, key: WirelessChannelKey): Pair<Int, Int> {
        val ordered = getOrderedAnchors(level, key).toMutableList()
        if (ordered.size <= 1) return 1 to ordered.size.coerceAtLeast(1)

        val idx = ordered.indexOf(pos)
        if (idx < 0) return 1 to ordered.size

        val nextIdx = (idx + 1) % ordered.size
        val moving = ordered.removeAt(idx)
        ordered.add(nextIdx, moving)

        val wb = bucket(level)
        for ((rankIdx, anchorPos) in ordered.withIndex()) {
            val normalizedOrder = (rankIdx + 1).toLong()
            wb.orderByPos[anchorPos] = normalizedOrder
            val be = level.getBlockEntity(anchorPos) as? AmbitAnchorBlockEntity
            be?.applyNormalizedOrder(normalizedOrder)
        }
        return (nextIdx + 1) to ordered.size
    }

    /**
     * 在世界中沿着当前多边形连线喷射细微粒子（切换生效或调整序号时提供空间反馈）。
     */
    fun spawnPolygonOutlineParticles(level: ServerLevel, anchors: List<BlockPos>, maxParticles: Int = 96) {
        if (anchors.size < 2) return
        val stepCountPerEdge = max(2, maxParticles / anchors.size).coerceAtMost(12)
        for (i in anchors.indices) {
            val a = Vec3.atCenterOf(anchors[i])
            val b = Vec3.atCenterOf(anchors[(i + 1) % anchors.size])
            for (s in 0 until stepCountPerEdge) {
                val t = s.toDouble() / stepCountPerEdge
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t + 0.15
                val pz = a.z + (b.z - a.z) * t
                level.sendParticles(ParticleTypes.WITCH, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
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
        CastingEnvironment.addCreateEventListener { env, userData ->
            if (env !is CircleCastEnv) return@addCreateEventListener
            val shape = readShapeFromUserData(userData) ?: return@addCreateEventListener
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

    fun writeShapeToUserData(userData: CompoundTag, shape: AmbitPolygonShape): CompoundTag {
        val copy = userData.copy()
        copy.put(TAG_ACTIVE_AMBIT, shape.toNbt())
        return copy
    }

    fun clearShapeFromUserData(userData: CompoundTag): CompoundTag {
        val copy = userData.copy()
        copy.remove(TAG_ACTIVE_AMBIT)
        return copy
    }

    fun readShapeFromUserData(userData: CompoundTag?): AmbitPolygonShape? {
        if (userData == null || !userData.contains(TAG_ACTIVE_AMBIT, Tag.TAG_COMPOUND.toInt())) return null
        return AmbitPolygonShape.fromNbt(userData.getCompound(TAG_ACTIVE_AMBIT))
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
