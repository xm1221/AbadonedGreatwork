package cn.xm1221.abadoned_greatwork.client.renderer

import at.petrak.hexcasting.api.pigment.FrozenPigment
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.api.pigmentFromNbt
import cn.xm1221.abadoned_greatwork.networking.msg.MsgSyncAmbitLinesS2C
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 塑界道引标激活态闭环“标定线”世界渲染器：
 * - 替代原本短暂消散的轮廓粒子，持续连接激活中的各道引标光球中心并闭合；
 * - 颜色优先级与光球完全一致（施法者定制染色实时取色优先，否则回落至栈顶 Iota 类型配色）；
 * - 随促动石切换范围或结束施法同步熄灭。
 */
object AmbitBoundaryRenderer {

    private const val ORB_CENTER_Y_OFFSET = 1.125 // 12px / 16 * 1.5x RENDER_SCALE
    private const val CORE_HALF_RADIUS = 0.018f
    private const val HALO_HALF_RADIUS = 0.044f
    private const val HALO_ALPHA = 95
    private const val PIGMENT_STEP_BLOCKS = 1.5
    private const val MAX_STEPS_PER_EDGE = 24

    private val TEX_ANCHOR = ResourceLocation.fromNamespaceAndPath(Abadoned_greatwork.MODID, "block/ambit_anchor")

    private class CachedLoop(
        val points: List<Vec3>,
        val pigment: FrozenPigment?,
        val fallbackColor: Int,
    )

    @Volatile
    private var activeDimension: ResourceLocation? = null

    @Volatile
    private var activeLoops: List<CachedLoop> = emptyList()

    fun updateLoops(dimension: ResourceLocation, rawLoops: List<MsgSyncAmbitLinesS2C.AmbitLineLoop>) {
        activeDimension = dimension
        activeLoops = rawLoops.mapNotNull { loop ->
            if (loop.anchors.size < 2) return@mapNotNull null
            val pts = loop.anchors.map { pos ->
                Vec3(pos.x + 0.5, pos.y + ORB_CENTER_Y_OFFSET, pos.z + 0.5)
            }
            val pigment = loop.pigmentTag?.let { pigmentFromNbt(it, Minecraft.getInstance().connection?.registryAccess()) }
            CachedLoop(pts, pigment, loop.fallbackColor)
        }
    }

    fun clear() {
        activeDimension = null
        activeLoops = emptyList()
    }

    fun render(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraPos: Vec3,
        partialTick: Float,
    ) {
        val loops = activeLoops
        if (loops.isEmpty()) return

        val level = Minecraft.getInstance().level ?: return
        if (level.dimension().location() != activeDimension) return

        val sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(TEX_ANCHOR)
        val u0 = sprite.getU(0.1f)
        val v0 = sprite.getV(0.1f)
        val u1 = sprite.getU(0.4f)
        val v1 = sprite.getV(0.4f)

        val coreVc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS))
        val haloVc = bufferSource.getBuffer(RenderType.lightning())

        val gameTime = level.gameTime + partialTick

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        val poseMat = poseStack.last().pose()
        val normMat = poseStack.last().normal()

        for (loop in loops) {
            val pts = loop.points
            val count = pts.size
            for (i in 0 until count) {
                val a = pts[i]
                val b = pts[(i + 1) % count]
                renderEdge(
                    poseMat = poseMat,
                    normMat = normMat,
                    coreVc = coreVc,
                    haloVc = haloVc,
                    start = a,
                    end = b,
                    u0 = u0, v0 = v0, u1 = u1, v1 = v1,
                    pigment = loop.pigment,
                    fallbackColor = loop.fallbackColor,
                    gameTime = gameTime,
                )
            }
        }

        poseStack.popPose()
    }

    private fun renderEdge(
        poseMat: Matrix4f,
        normMat: Matrix3f,
        coreVc: VertexConsumer,
        haloVc: VertexConsumer,
        start: Vec3,
        end: Vec3,
        u0: Float, v0: Float, u1: Float, v1: Float,
        pigment: FrozenPigment?,
        fallbackColor: Int,
        gameTime: Float,
    ) {
        val delta = end.subtract(start)
        val len = delta.length()
        if (len < 1.0e-4) return

        val dir = delta.scale(1.0 / len)
        // 构造与线段方向垂直的正交基底 (basisU, basisV)
        val upHint = if (abs(dir.y) > 0.92) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val basisU = dir.cross(upHint).normalize()
        val basisV = basisU.cross(dir).normalize()

        val steps = if (pigment != null) {
            ceil(len / PIGMENT_STEP_BLOCKS).toInt().coerceIn(1, MAX_STEPS_PER_EDGE)
        } else {
            1
        }

        var prevPoint = start
        var prevArgb = sampleColor(pigment, fallbackColor, gameTime, prevPoint)

        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val nextPoint = if (step == steps) end else start.lerp(end, t)
            val nextArgb = sampleColor(pigment, fallbackColor, gameTime, nextPoint)

            emitPrismSegment(
                poseMat, normMat, coreVc, haloVc,
                prevPoint, nextPoint, basisU, basisV,
                prevArgb, nextArgb,
                u0, v0, u1, v1,
            )

            prevPoint = nextPoint
            prevArgb = nextArgb
        }
    }

    private fun sampleColor(
        pigment: FrozenPigment?,
        fallbackColor: Int,
        gameTime: Float,
        pos: Vec3,
    ): Int = pigment?.colorProvider?.getColor(gameTime * 0.5f, pos) ?: fallbackColor

    private fun emitPrismSegment(
        poseMat: Matrix4f,
        normMat: Matrix3f,
        coreVc: VertexConsumer,
        haloVc: VertexConsumer,
        p0: Vec3,
        p1: Vec3,
        basisU: Vec3,
        basisV: Vec3,
        argb0: Int,
        argb1: Int,
        u0: Float, v0: Float, u1: Float, v1: Float,
    ) {
        val r0 = (argb0 ushr 16) and 0xFF
        val g0 = (argb0 ushr 8) and 0xFF
        val b0 = argb0 and 0xFF

        val r1 = (argb1 ushr 16) and 0xFF
        val g1 = (argb1 ushr 8) and 0xFF
        val b1 = argb1 and 0xFF

        emitPrismShell(
            poseMat, normMat, coreVc,
            p0, p1, basisU, basisV, CORE_HALF_RADIUS,
            r0, g0, b0, 255, r1, g1, b1, 255,
            textured = true,
            u0 = u0, v0 = v0, u1 = u1, v1 = v1,
        )
        emitPrismShell(
            poseMat, normMat, haloVc,
            p0, p1, basisU, basisV, HALO_HALF_RADIUS,
            r0, g0, b0, HALO_ALPHA, r1, g1, b1, HALO_ALPHA,
            textured = false,
            u0 = 0f, v0 = 0f, u1 = 0f, v1 = 0f,
        )
    }

    private fun emitPrismShell(
        poseMat: Matrix4f,
        normMat: Matrix3f,
        vc: VertexConsumer,
        p0: Vec3,
        p1: Vec3,
        basisU: Vec3,
        basisV: Vec3,
        radius: Float,
        r0: Int, g0: Int, b0: Int, a0: Int,
        r1: Int, g1: Int, b1: Int, a1: Int,
        textured: Boolean,
        u0: Float, v0: Float, u1: Float, v1: Float,
    ) {
        val ux = basisU.x.toFloat() * radius
        val uy = basisU.y.toFloat() * radius
        val uz = basisU.z.toFloat() * radius

        val vx = basisV.x.toFloat() * radius
        val vy = basisV.y.toFloat() * radius
        val vz = basisV.z.toFloat() * radius

        val x0 = p0.x.toFloat()
        val y0 = p0.y.toFloat()
        val z0 = p0.z.toFloat()
        val x1 = p1.x.toFloat()
        val y1 = p1.y.toFloat()
        val z1 = p1.z.toFloat()

        // 四角柱四个侧角点偏移: (+U,+V), (-U,+V), (-U,-V), (+U,-V)
        val c0x0 = x0 + ux + vx; val c0y0 = y0 + uy + vy; val c0z0 = z0 + uz + vz
        val c1x0 = x0 - ux + vx; val c1y0 = y0 - uy + vy; val c1z0 = z0 - uz + vz
        val c2x0 = x0 - ux - vx; val c2y0 = y0 - uy - vy; val c2z0 = z0 - uz - vz
        val c3x0 = x0 + ux - vx; val c3y0 = y0 + uy - vy; val c3z0 = z0 + uz - vz

        val c0x1 = x1 + ux + vx; val c0y1 = y1 + uy + vy; val c0z1 = z1 + uz + vz
        val c1x1 = x1 - ux + vx; val c1y1 = y1 - uy + vy; val c1z1 = z1 - uz + vz
        val c2x1 = x1 - ux - vx; val c2y1 = y1 - uy - vy; val c2z1 = z1 - uz - vz
        val c3x1 = x1 + ux - vx; val c3y1 = y1 + uy - vy; val c3z1 = z1 + uz - vz

        emitQuad(
            poseMat, normMat, vc, textured,
            c0x0, c0y0, c0z0, c1x0, c1y0, c1z0, c1x1, c1y1, c1z1, c0x1, c0y1, c0z1,
            r0, g0, b0, a0, r1, g1, b1, a1, u0, v0, u1, v1,
        )
        emitQuad(
            poseMat, normMat, vc, textured,
            c1x0, c1y0, c1z0, c2x0, c2y0, c2z0, c2x1, c2y1, c2z1, c1x1, c1y1, c1z1,
            r0, g0, b0, a0, r1, g1, b1, a1, u0, v0, u1, v1,
        )
        emitQuad(
            poseMat, normMat, vc, textured,
            c2x0, c2y0, c2z0, c3x0, c3y0, c3z0, c3x1, c3y1, c3z1, c2x1, c2y1, c2z1,
            r0, g0, b0, a0, r1, g1, b1, a1, u0, v0, u1, v1,
        )
        emitQuad(
            poseMat, normMat, vc, textured,
            c3x0, c3y0, c3z0, c0x0, c0y0, c0z0, c0x1, c0y1, c0z1, c3x1, c3y1, c3z1,
            r0, g0, b0, a0, r1, g1, b1, a1, u0, v0, u1, v1,
        )
    }

    private fun emitQuad(
        poseMat: Matrix4f,
        normMat: Matrix3f,
        vc: VertexConsumer,
        textured: Boolean,
        ax0: Float, ay0: Float, az0: Float,
        bx0: Float, by0: Float, bz0: Float,
        bx1: Float, by1: Float, bz1: Float,
        ax1: Float, ay1: Float, az1: Float,
        r0: Int, g0: Int, b0: Int, a0: Int,
        r1: Int, g1: Int, b1: Int, a1: Int,
        u0: Float, v0: Float, u1: Float, v1: Float,
    ) {
        if (textured) {
            emitTexturedVertex(vc, poseMat, normMat, ax0, ay0, az0, r0, g0, b0, a0, u0, v0)
            emitTexturedVertex(vc, poseMat, normMat, bx0, by0, bz0, r0, g0, b0, a0, u1, v0)
            emitTexturedVertex(vc, poseMat, normMat, bx1, by1, bz1, r1, g1, b1, a1, u1, v1)
            emitTexturedVertex(vc, poseMat, normMat, ax1, ay1, az1, r1, g1, b1, a1, u0, v1)
        } else {
            vc.addVertex(poseMat, ax0, ay0, az0).setColor(r0, g0, b0, a0)
            vc.addVertex(poseMat, bx0, by0, bz0).setColor(r0, g0, b0, a0)
            vc.addVertex(poseMat, bx1, by1, bz1).setColor(r1, g1, b1, a1)
            vc.addVertex(poseMat, ax1, ay1, az1).setColor(r1, g1, b1, a1)
        }
    }

    private fun emitTexturedVertex(
        vc: VertexConsumer,
        poseMat: Matrix4f,
        normMat: Matrix3f,
        x: Float, y: Float, z: Float,
        r: Int, g: Int, b: Int, a: Int,
        u: Float, v: Float,
    ) {
        val normal = org.joml.Vector3f(0f, 1f, 0f)
        normMat.transform(normal)
        vc.addVertex(poseMat, x, y, z)
            .setColor(r, g, b, a)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT)
            .setNormal(normal.x, normal.y, normal.z)
            
    }
}
