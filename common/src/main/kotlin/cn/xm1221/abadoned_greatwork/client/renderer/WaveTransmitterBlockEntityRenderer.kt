package cn.xm1221.abadoned_greatwork.client.renderer

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.WaveTransmitterBlock
import cn.xm1221.abadoned_greatwork.block.entity.WaveTransmitterBlockEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import org.joml.Matrix3f
import org.joml.Matrix4f
import kotlin.math.sin

/**
 * 队长发信石方块实体渲染器：
 * - 静止底层底座与励磁线圈由静态方块模型渲染；
 * - 激活时（含短脉冲客户端余辉保持期间）平滑向上举起可伸缩桅杆、横杆与旗帜，并带有微幅电磁风吹摆动。
 */
class WaveTransmitterBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<WaveTransmitterBlockEntity> {

    override fun render(
        be: WaveTransmitterBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val state = be.blockState
        val facing = state.getValue(WaveTransmitterBlock.FACING)
        val flagRot = state.getValue(WaveTransmitterBlock.FLAG_ROT)
        val energized = state.getValue(BlockCircleComponent.ENERGIZED)

        val now = level.gameTime + partialTick.toDouble()
        if (energized) {
            be.clientHoldUntilTime = maxOf(be.clientHoldUntilTime, now + HOLD_TICKS)
        }
        val active = energized || now < be.clientHoldUntilTime

        val prevTime = be.clientLastRenderTime
        val dt = if (prevTime < 0.0 || now < prevTime) 0.5f else (now - prevTime).toFloat().coerceIn(0f, 4f)
        be.clientLastRenderTime = now

        val target = if (active) 1f else 0f
        val rate = if (target > be.clientRaiseProgress) RAISE_SPEED else LOWER_SPEED
        val step = rate * dt
        be.clientRaiseProgress = when {
            be.clientRaiseProgress < target -> minOf(target, be.clientRaiseProgress + step)
            be.clientRaiseProgress > target -> maxOf(target, be.clientRaiseProgress - step)
            else -> target
        }

        val eased = smoothstep(be.clientRaiseProgress)
        val liftPx = eased * MAX_LIFT_PX

        val texId = if (active || be.clientRaiseProgress > 0.15f) TEX_ENERGIZED else TEX_IDLE
        val sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texId)
        val vc = bufferSource.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS))

        poseStack.pushPose()
        applyBlockStateRotation(poseStack, facing, flagRot)

        // 1. 伸缩桅杆随举旗高度动态拉长，避免底座与旗杆脱节
        if (liftPx > 0.05f) {
            renderBox(
                poseStack, vc, sprite, packedLight, packedOverlay,
                7f, 10f, 7f, 9f, 14f + liftPx, 9f,
                UvRect(0f, 9f, 1f, 14.5f),
                UvRect(0f, 9f, 1f, 10f),
            )
        } else {
            renderBox(
                poseStack, vc, sprite, packedLight, packedOverlay,
                7f, 11f, 7f, 9f, 14f, 9f,
                UvRect(0f, 9f, 1f, 10.5f),
                UvRect(0f, 9f, 1f, 10f),
            )
        }

        // 2. 其余旗杆附件整体上移 liftPx
        poseStack.translate(0.0, (liftPx / 16f).toDouble(), 0.0)

        // crossbar_bracket
        renderBoxUniform(
            poseStack, vc, sprite, packedLight, packedOverlay,
            7f, 13f, 6f, 9f, 14f, 7f,
            north = UvRect(1f, 9f, 2f, 9.5f),
            east = UvRect(1f, 9f, 1.5f, 9.5f),
            south = UvRect(1f, 9f, 2f, 9.5f),
            west = UvRect(1f, 9f, 1.5f, 9.5f),
            up = UvRect(1f, 9f, 2f, 9.5f),
            down = UvRect(1f, 9f, 2f, 9.5f),
        )

        // banner_crossbar
        renderBoxUniform(
            poseStack, vc, sprite, packedLight, packedOverlay,
            3f, 13f, 4f, 13f, 14f, 6f,
            north = UvRect(1f, 9f, 6f, 9.5f),
            east = UvRect(1f, 9f, 2f, 9.5f),
            south = UvRect(1f, 9f, 6f, 9.5f),
            west = UvRect(1f, 9f, 2f, 9.5f),
            up = UvRect(1f, 9f, 6f, 9.5f),
            down = UvRect(1f, 9f, 6f, 9.5f),
        )

        // finial_collar
        renderBoxUniform(
            poseStack, vc, sprite, packedLight, packedOverlay,
            6f, 14f, 6f, 10f, 15f, 10f,
            north = UvRect(1f, 10f, 3f, 10.5f),
            east = UvRect(1f, 10f, 3f, 10.5f),
            south = UvRect(1f, 10f, 3f, 10.5f),
            west = UvRect(1f, 10f, 3f, 10.5f),
            up = UvRect(8.5f, 0.5f, 10.5f, 2.5f),
            down = UvRect(8.5f, 0.5f, 10.5f, 2.5f),
        )

        // finial_tip
        renderBoxUniform(
            poseStack, vc, sprite, packedLight, packedOverlay,
            7f, 15f, 7f, 9f, 16f, 9f,
            north = UvRect(1f, 11f, 2f, 11.5f),
            east = UvRect(1f, 11f, 2f, 11.5f),
            south = UvRect(1f, 11f, 2f, 11.5f),
            west = UvRect(1f, 11f, 2f, 11.5f),
            up = UvRect(1f, 11f, 2f, 12f),
            down = UvRect(1f, 11f, 2f, 12f),
        )

        // captain_banner：举起后绕横杆边缘微幅迎风摆动
        poseStack.pushPose()
        if (eased > 0.02f) {
            val sway = (-2.5 - sin(now * 0.28 + be.blockPos.hashCode() * 0.1) * 3.0) * eased
            poseStack.translate(0.5, 13.0 / 16.0, 4.5 / 16.0)
            poseStack.mulPose(Axis.XP.rotationDegrees(sway.toFloat()))
            poseStack.translate(-0.5, -13.0 / 16.0, -4.5 / 16.0)
        }
        renderBoxUniform(
            poseStack, vc, sprite, packedLight, packedOverlay,
            4f, 4f, 4f, 12f, 13f, 5f,
            north = UvRect(7f, 10.5f, 12f, 16f),
            east = UvRect(12f, 7f, 12.5f, 11f),
            south = UvRect(8f, 7f, 12f, 11f),
            west = UvRect(15.5f, 7f, 16f, 11f),
            up = UvRect(12f, 7f, 16f, 7.5f),
            down = UvRect(12f, 10.5f, 16f, 11f),
        )
        poseStack.popPose()

        poseStack.popPose()
    }

    private fun applyBlockStateRotation(poseStack: PoseStack, facing: Direction, flagRot: Int) {
        val (rx, ry) = when (facing) {
            Direction.UP -> 0f to 0f
            Direction.DOWN -> 180f to 0f
            Direction.NORTH -> 90f to 0f
            Direction.EAST -> 90f to 90f
            Direction.SOUTH -> 90f to 180f
            Direction.WEST -> 90f to 270f
        }
        val spinY = (flagRot and 3) * 90f
        if (rx == 0f && ry == 0f && spinY == 0f) return
        poseStack.translate(0.5, 0.5, 0.5)
        if (ry != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(-ry))
        if (rx != 0f) poseStack.mulPose(Axis.XP.rotationDegrees(-rx))
        if (spinY != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(-spinY))
        poseStack.translate(-0.5, -0.5, -0.5)
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private data class UvRect(val u1: Float, val v1: Float, val u2: Float, val v2: Float)

    private fun renderBox(
        poseStack: PoseStack,
        vc: VertexConsumer,
        sprite: TextureAtlasSprite,
        light: Int,
        overlay: Int,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        side: UvRect,
        cap: UvRect,
    ) = renderBoxUniform(
        poseStack, vc, sprite, light, overlay,
        x1, y1, z1, x2, y2, z2,
        north = side, east = side, south = side, west = side, up = cap, down = cap,
    )

    private fun renderBoxUniform(
        poseStack: PoseStack,
        vc: VertexConsumer,
        sprite: TextureAtlasSprite,
        light: Int,
        overlay: Int,
        px1: Float,
        py1: Float,
        pz1: Float,
        px2: Float,
        py2: Float,
        pz2: Float,
        north: UvRect,
        east: UvRect,
        south: UvRect,
        west: UvRect,
        up: UvRect,
        down: UvRect,
    ) {
        val x1 = px1 / 16f
        val y1 = py1 / 16f
        val z1 = pz1 / 16f
        val x2 = px2 / 16f
        val y2 = py2 / 16f
        val z2 = pz2 / 16f

        val pose = poseStack.last()
        val mat = pose.pose()
        val nMat = pose.normal()

        // NORTH (-Z)
        emitQuad(mat, nMat, vc, sprite, light, overlay, 0f, 0f, -1f,
            x2, y2, z1, north.u1, north.v1,
            x2, y1, z1, north.u1, north.v2,
            x1, y1, z1, north.u2, north.v2,
            x1, y2, z1, north.u2, north.v1,
        )
        // SOUTH (+Z)
        emitQuad(mat, nMat, vc, sprite, light, overlay, 0f, 0f, 1f,
            x1, y2, z2, south.u1, south.v1,
            x1, y1, z2, south.u1, south.v2,
            x2, y1, z2, south.u2, south.v2,
            x2, y2, z2, south.u2, south.v1,
        )
        // EAST (+X)
        emitQuad(mat, nMat, vc, sprite, light, overlay, 1f, 0f, 0f,
            x2, y2, z2, east.u1, east.v1,
            x2, y1, z2, east.u1, east.v2,
            x2, y1, z1, east.u2, east.v2,
            x2, y2, z1, east.u2, east.v1,
        )
        // WEST (-X)
        emitQuad(mat, nMat, vc, sprite, light, overlay, -1f, 0f, 0f,
            x1, y2, z1, west.u1, west.v1,
            x1, y1, z1, west.u1, west.v2,
            x1, y1, z2, west.u2, west.v2,
            x1, y2, z2, west.u2, west.v1,
        )
        // UP (+Y)
        emitQuad(mat, nMat, vc, sprite, light, overlay, 0f, 1f, 0f,
            x1, y2, z1, up.u1, up.v1,
            x1, y2, z2, up.u1, up.v2,
            x2, y2, z2, up.u2, up.v2,
            x2, y2, z1, up.u2, up.v1,
        )
        // DOWN (-Y)
        emitQuad(mat, nMat, vc, sprite, light, overlay, 0f, -1f, 0f,
            x1, y1, z2, down.u1, down.v1,
            x1, y1, z1, down.u1, down.v2,
            x2, y1, z1, down.u2, down.v2,
            x2, y1, z2, down.u2, down.v1,
        )
    }

    private fun emitQuad(
        mat: Matrix4f,
        nMat: Matrix3f,
        vc: VertexConsumer,
        sprite: TextureAtlasSprite,
        light: Int,
        overlay: Int,
        nx: Float,
        ny: Float,
        nz: Float,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
    ) {
        emitVertex(mat, nMat, vc, sprite, light, overlay, nx, ny, nz, x0, y0, z0, u0, v0)
        emitVertex(mat, nMat, vc, sprite, light, overlay, nx, ny, nz, x1, y1, z1, u1, v1)
        emitVertex(mat, nMat, vc, sprite, light, overlay, nx, ny, nz, x2, y2, z2, u2, v2)
        emitVertex(mat, nMat, vc, sprite, light, overlay, nx, ny, nz, x3, y3, z3, u3, v3)
    }

    private fun emitVertex(
        mat: Matrix4f,
        nMat: Matrix3f,
        vc: VertexConsumer,
        sprite: TextureAtlasSprite,
        light: Int,
        overlay: Int,
        nx: Float,
        ny: Float,
        nz: Float,
        x: Float,
        y: Float,
        z: Float,
        u16: Float,
        v16: Float,
    ) {
        val u = Mth.lerp(u16 / 16f, sprite.u0, sprite.u1)
        val v = Mth.lerp(v16 / 16f, sprite.v0, sprite.v1)
        vc.vertex(mat, x, y, z)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .overlayCoords(overlay)
            .uv2(light)
            .normal(nMat, nx, ny, nz)
            .endVertex()
    }

    companion object {
        private const val MAX_LIFT_PX = 9.0f
        private const val HOLD_TICKS = 18.0
        private const val RAISE_SPEED = 0.22f
        private const val LOWER_SPEED = 0.11f

        private val TEX_IDLE = ResourceLocation(Abadoned_greatwork.MODID, "block/wave_transmitter")
        private val TEX_ENERGIZED = ResourceLocation(Abadoned_greatwork.MODID, "block/wave_transmitter_energized")
    }
}
