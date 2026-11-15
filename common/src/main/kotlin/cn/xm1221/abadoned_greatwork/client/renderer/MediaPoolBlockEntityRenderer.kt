package cn.xm1221.abadoned_greatwork.client.renderer

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.entity.MediaPoolBlockEntity
import com.mojang.blaze3d.Blaze3D
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import org.joml.Matrix3f
import org.joml.Matrix4f
import kotlin.math.exp

/**
 * 媒质池（`media_pool`）动态液面渲染器：
 * - 根据方块实体储量占比在容器内壁 `[3, 5..11.95, 3] .. [13, ..., 13]` 之间平滑升降液面；
 * - 激活态切换为高亮液面纹理并提升亮度，透过池壁玻璃窗从侧面或俯视皆可直观读取剩余储量。
 */
class MediaPoolBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<MediaPoolBlockEntity> {

    override fun render(
        be: MediaPoolBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val targetFill = be.fillRatio()
        val nowSec = Blaze3D.getTime()
        if (be.clientDisplayedFill < 0f || be.clientLastRenderTime < 0.0) {
            be.clientDisplayedFill = targetFill
        } else {
            val dt = (nowSec - be.clientLastRenderTime).toFloat().coerceIn(0f, 0.25f)
            val alpha = (1f - exp((-10.0f * dt).toDouble()).toFloat()).coerceIn(0f, 1f)
            be.clientDisplayedFill += (targetFill - be.clientDisplayedFill) * alpha
        }
        be.clientLastRenderTime = nowSec

        val displayedFill = be.clientDisplayedFill.coerceIn(0f, 1f)
        if (displayedFill <= 0.001f) return

        val state = be.blockState
        val energized = state.hasProperty(BlockCircleComponent.ENERGIZED) &&
            state.getValue(BlockCircleComponent.ENERGIZED)

        val texId = if (energized) TEX_ENERGIZED else TEX_IDLE
        val sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texId)
        val vc = bufferSource.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS))
        val light = if (energized) LightTexture.FULL_BRIGHT else packedLight

        val x0 = 3.005f / 16f
        val x1 = 12.995f / 16f
        val z0 = 3.005f / 16f
        val z1 = 12.995f / 16f
        val y0 = 5.002f / 16f
        val y1 = (5.05f + 6.90f * displayedFill) / 16f

        val pose = poseStack.last()
        val poseMat = pose.pose()
        val normMat = pose.normal()

        // 顶面：对应贴图 (u=8..15, v=0..7)
        val topU0 = sprite.getU(8.0)
        val topU1 = sprite.getU(15.0)
        val topV0 = sprite.getV(0.0)
        val topV1 = sprite.getV(7.0)
        quad(
            vc, poseMat, normMat, light, packedOverlay,
            x0, y1, z0, topU0, topV0,
            x0, y1, z1, topU0, topV1,
            x1, y1, z1, topU1, topV1,
            x1, y1, z0, topU1, topV0,
            0f, 1f, 0f,
        )

        // 四个侧面：对应贴图 (u=8..15, v=0..1)
        val sideU0 = sprite.getU(8.0)
        val sideU1 = sprite.getU(15.0)
        val sideV0 = sprite.getV(0.0)
        val sideV1 = sprite.getV(1.0)

        // North (-Z)
        quad(
            vc, poseMat, normMat, light, packedOverlay,
            x1, y1, z0, sideU0, sideV0,
            x1, y0, z0, sideU0, sideV1,
            x0, y0, z0, sideU1, sideV1,
            x0, y1, z0, sideU1, sideV0,
            0f, 0f, -1f,
        )
        // South (+Z)
        quad(
            vc, poseMat, normMat, light, packedOverlay,
            x0, y1, z1, sideU0, sideV0,
            x0, y0, z1, sideU0, sideV1,
            x1, y0, z1, sideU1, sideV1,
            x1, y1, z1, sideU1, sideV0,
            0f, 0f, 1f,
        )
        // West (-X)
        quad(
            vc, poseMat, normMat, light, packedOverlay,
            x0, y1, z0, sideU0, sideV0,
            x0, y0, z0, sideU0, sideV1,
            x0, y0, z1, sideU1, sideV1,
            x0, y1, z1, sideU1, sideV0,
            -1f, 0f, 0f,
        )
        // East (+X)
        quad(
            vc, poseMat, normMat, light, packedOverlay,
            x1, y1, z1, sideU0, sideV0,
            x1, y0, z1, sideU0, sideV1,
            x1, y0, z0, sideU1, sideV1,
            x1, y1, z0, sideU1, sideV0,
            1f, 0f, 0f,
        )
    }

    private fun quad(
        vc: VertexConsumer,
        pose: Matrix4f,
        norm: Matrix3f,
        light: Int,
        overlay: Int,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        vertex(vc, pose, norm, light, overlay, x0, y0, z0, u0, v0, nx, ny, nz)
        vertex(vc, pose, norm, light, overlay, x1, y1, z1, u1, v1, nx, ny, nz)
        vertex(vc, pose, norm, light, overlay, x2, y2, z2, u2, v2, nx, ny, nz)
        vertex(vc, pose, norm, light, overlay, x3, y3, z3, u3, v3, nx, ny, nz)
    }

    private fun vertex(
        vc: VertexConsumer,
        pose: Matrix4f,
        norm: Matrix3f,
        light: Int,
        overlay: Int,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        nx: Float,
        ny: Float,
        nz: Float,
    ) {
        vc.vertex(pose, x, y, z)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .overlayCoords(overlay)
            .uv2(light)
            .normal(norm, nx, ny, nz)
            .endVertex()
    }

    companion object {
        private val TEX_IDLE = ResourceLocation(Abadoned_greatwork.MODID, "block/media_pool")
        private val TEX_ENERGIZED = ResourceLocation(Abadoned_greatwork.MODID, "block/media_pool_energized")
    }
}
