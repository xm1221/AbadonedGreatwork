package cn.xm1221.abadoned_greatwork.client.renderer

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f

/**
 * 道引标（`ambit_anchor`）方块实体渲染器：
 * - 不改原始 Blockbench 建模尺寸，在世界渲染阶段以底座中心为原点整体放大至 [RENDER_SCALE] 倍；
 * - 平时仅渲染板岩基座与铁柱；仅当处于激活态（`be.orbActive == true`）时才渲染顶部亮起的多层光球，
 *   颜色优先取施法者染色（支持动态染色），未设置定制染色时回落到栈顶 Iota 类型配色。
 */
class AmbitAnchorBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<AmbitAnchorBlockEntity> {

    private data class UvRect(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    override fun render(
        be: AmbitAnchorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
        val anchorSprite = atlas.apply(TEX_ANCHOR)
        val slateSprite = atlas.apply(TEX_SLATE)
        val vc = bufferSource.getBuffer(RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS))

        poseStack.pushPose()
        poseStack.translate(0.5, 0.0, 0.5)
        poseStack.scale(RENDER_SCALE, RENDER_SCALE, RENDER_SCALE)
        poseStack.translate(-0.5, 0.0, -0.5)

        // ===== 1. 静态底座与柱身（ambit_anchor.json elements 0..5，原色世界光照） =====
        // slate_plinth
        renderBox(
            poseStack, vc, slateSprite, packedLight, packedOverlay,
            4f, 0f, 4f, 12f, 2f, 12f,
            sideUv = UvRect(0f, 0f, 16f, 2f),
            capUv = UvRect(2f, 2f, 14f, 14f),
        )
        // slate_step
        renderBox(
            poseStack, vc, slateSprite, packedLight, packedOverlay,
            5f, 2f, 5f, 11f, 3f, 11f,
            sideUv = UvRect(0f, 14f, 16f, 16f),
            capUv = UvRect(2f, 2f, 14f, 14f),
        )
        // iron_foot_collar
        renderBox(
            poseStack, vc, anchorSprite, packedLight, packedOverlay,
            6f, 3f, 6f, 10f, 4f, 10f,
            sideUv = UvRect(8f, 6f, 16f, 8f),
            capUv = UvRect(8f, 0f, 16f, 8f),
        )
        // pylon_shaft
        renderBox(
            poseStack, vc, anchorSprite, packedLight, packedOverlay,
            7f, 4f, 7f, 9f, 9f, 9f,
            sideUv = UvRect(8f, 8f, 16f, 16f),
            capUv = UvRect(8f, 8f, 16f, 16f),
        )
        // iron_mid_band
        renderBox(
            poseStack, vc, anchorSprite, packedLight, packedOverlay,
            6f, 6f, 6f, 10f, 7f, 10f,
            sideUv = UvRect(8f, 6f, 16f, 8f),
            capUv = UvRect(8f, 0f, 16f, 8f),
        )
        // iron_crown_collar
        renderBox(
            poseStack, vc, anchorSprite, packedLight, packedOverlay,
            6f, 9f, 6f, 10f, 10f, 10f,
            sideUv = UvRect(8f, 6f, 16f, 8f),
            capUv = UvRect(8f, 0f, 16f, 8f),
        )

        // ===== 2. 激活态顶部光球（elements 6..10，全亮 + 施法者/Iota 染色） =====
        if (be.orbActive) {
            val gameTime = (level.gameTime + partialTick)
            val bobPx = Mth.sin(gameTime * 0.1f) * 0.35f
            poseStack.translate(0.0, (bobPx / 16f).toDouble(), 0.0)

            val pigment = be.resolveClientPigment()
            val argb = if (pigment != null) {
                pigment.colorProvider.getColor(gameTime * 0.5f, Vec3.atCenterOf(be.blockPos))
            } else {
                be.orbFallbackColor
            }
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            // 核心球体 (element 6)
            renderBox(
                poseStack, vc, anchorSprite, LightTexture.FULL_BRIGHT, packedOverlay,
                7f, 11f, 7f, 9f, 13f, 9f,
                sideUv = UvRect(0f, 4f, 4f, 8f),
                capUv = UvRect(0f, 4f, 4f, 8f),
                r = r, g = g, b = b,
            )

            // 多层反向外壳光晕 (elements 7..10 cube_outline)
            renderBox(
                poseStack, vc, anchorSprite, LightTexture.FULL_BRIGHT, packedOverlay,
                6.2f, 10.2f, 6.2f, 9.8f, 13.8f, 9.8f,
                sideUv = UvRect(1f, 0f, 2f, 1f),
                capUv = UvRect(1f, 0f, 2f, 1f),
                r = r, g = g, b = b, inverted = true,
            )
            renderBox(
                poseStack, vc, anchorSprite, LightTexture.FULL_BRIGHT, packedOverlay,
                6.5f, 10.5f, 6.5f, 9.5f, 13.5f, 9.5f,
                sideUv = UvRect(2f, 0f, 3f, 1f),
                capUv = UvRect(2f, 0f, 3f, 1f),
                r = r, g = g, b = b, inverted = true,
            )
            renderBox(
                poseStack, vc, anchorSprite, LightTexture.FULL_BRIGHT, packedOverlay,
                6.7f, 10.7f, 6.7f, 9.3f, 13.3f, 9.3f,
                sideUv = UvRect(5f, 0f, 6f, 1f),
                capUv = UvRect(5f, 0f, 6f, 1f),
                r = r, g = g, b = b, inverted = true,
            )
            renderBox(
                poseStack, vc, anchorSprite, LightTexture.FULL_BRIGHT, packedOverlay,
                6.9f, 10.9f, 6.9f, 9.1f, 13.1f, 9.1f,
                sideUv = UvRect(4f, 0f, 5f, 1f),
                capUv = UvRect(4f, 0f, 5f, 1f),
                r = r, g = g, b = b, inverted = true,
            )
        }

        poseStack.popPose()
    }

    private fun renderBox(
        poseStack: PoseStack,
        vc: VertexConsumer,
        sprite: TextureAtlasSprite,
        light: Int,
        overlay: Int,
        minPxX: Float, minPxY: Float, minPxZ: Float,
        maxPxX: Float, maxPxY: Float, maxPxZ: Float,
        sideUv: UvRect,
        capUv: UvRect,
        r: Int = 255,
        g: Int = 255,
        b: Int = 255,
        inverted: Boolean = false,
    ) {
        val x0 = minPxX / 16f
        val y0 = minPxY / 16f
        val z0 = minPxZ / 16f
        val x1 = maxPxX / 16f
        val y1 = maxPxY / 16f
        val z1 = maxPxZ / 16f

        val pose = poseStack.last()
        val poseMat = pose.pose()
        val normMat = pose.normal()

        val su0 = sprite.getU(sideUv.u0.toFloat())
        val su1 = sprite.getU(sideUv.u1.toFloat())
        val sv0 = sprite.getV(sideUv.v0.toFloat())
        val sv1 = sprite.getV(sideUv.v1.toFloat())

        val cu0 = sprite.getU(capUv.u0.toFloat())
        val cu1 = sprite.getU(capUv.u1.toFloat())
        val cv0 = sprite.getV(capUv.v0.toFloat())
        val cv1 = sprite.getV(capUv.v1.toFloat())

        // Up (+Y)
        quad(
            vc, poseMat, normMat, light, overlay, r, g, b, inverted,
            x0, y1, z0, cu0, cv0,
            x0, y1, z1, cu0, cv1,
            x1, y1, z1, cu1, cv1,
            x1, y1, z0, cu1, cv0,
            0f, 1f, 0f,
        )
        // Down (-Y)
        quad(
            vc, poseMat, normMat, light, overlay, r, g, b, inverted,
            x0, y0, z1, cu0, cv0,
            x0, y0, z0, cu0, cv1,
            x1, y0, z0, cu1, cv1,
            x1, y0, z1, cu1, cv0,
            0f, -1f, 0f,
        )
        // North (-Z)
        quad(
            vc, poseMat, normMat, light, overlay, r, g, b, inverted,
            x1, y1, z0, su0, sv0,
            x1, y0, z0, su0, sv1,
            x0, y0, z0, su1, sv1,
            x0, y1, z0, su1, sv0,
            0f, 0f, -1f,
        )
        // South (+Z)
        quad(
            vc, poseMat, normMat, light, overlay, r, g, b, inverted,
            x0, y1, z1, su0, sv0,
            x0, y0, z1, su0, sv1,
            x1, y0, z1, su1, sv1,
            x1, y1, z1, su1, sv0,
            0f, 0f, 1f,
        )
        // West (-X)
        quad(
            vc, poseMat, normMat, light, overlay, r, g, b, inverted,
            x0, y1, z0, su0, sv0,
            x0, y0, z0, su0, sv1,
            x0, y0, z1, su1, sv1,
            x0, y1, z1, su1, sv0,
            -1f, 0f, 0f,
        )
        // East (+X)
        quad(
            vc, poseMat, normMat, light, overlay, r, g, b, inverted,
            x1, y1, z1, su0, sv0,
            x1, y0, z1, su0, sv1,
            x1, y0, z0, su1, sv1,
            x1, y1, z0, su1, sv0,
            1f, 0f, 0f,
        )
    }

    private fun quad(
        vc: VertexConsumer,
        pose: Matrix4f,
        norm: Matrix3f,
        light: Int,
        overlay: Int,
        r: Int, g: Int, b: Int,
        inverted: Boolean,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        if (!inverted) {
            vertex(vc, pose, norm, light, overlay, r, g, b, x0, y0, z0, u0, v0, nx, ny, nz)
            vertex(vc, pose, norm, light, overlay, r, g, b, x1, y1, z1, u1, v1, nx, ny, nz)
            vertex(vc, pose, norm, light, overlay, r, g, b, x2, y2, z2, u2, v2, nx, ny, nz)
            vertex(vc, pose, norm, light, overlay, r, g, b, x3, y3, z3, u3, v3, nx, ny, nz)
        } else {
            vertex(vc, pose, norm, light, overlay, r, g, b, x0, y0, z0, u0, v0, -nx, -ny, -nz)
            vertex(vc, pose, norm, light, overlay, r, g, b, x3, y3, z3, u3, v3, -nx, -ny, -nz)
            vertex(vc, pose, norm, light, overlay, r, g, b, x2, y2, z2, u2, v2, -nx, -ny, -nz)
            vertex(vc, pose, norm, light, overlay, r, g, b, x1, y1, z1, u1, v1, -nx, -ny, -nz)
        }
    }

    private fun vertex(
        vc: VertexConsumer,
        pose: Matrix4f,
        norm: Matrix3f,
        light: Int,
        overlay: Int,
        r: Int, g: Int, b: Int,
        x: Float, y: Float, z: Float,
        u: Float, v: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        val normal = org.joml.Vector3f(nx, ny, nz)
        norm.transform(normal)
        vc.addVertex(pose, x, y, z)
            .setColor(r, g, b, 255)
            .setUv(u, v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(normal.x, normal.y, normal.z)
            
    }

    companion object {
        private const val RENDER_SCALE = 1.5f
        private val TEX_ANCHOR = ResourceLocation.fromNamespaceAndPath(Abadoned_greatwork.MODID, "block/ambit_anchor")
        private val TEX_SLATE = ResourceLocation.fromNamespaceAndPath(Abadoned_greatwork.MODID, "block/multi_directrix_0")
    }
}
