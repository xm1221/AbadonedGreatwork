package cn.xm1221.abadoned_greatwork.client.renderer

import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext

/**
 * 真理水晶基座方块实体渲染器：
 * 当基座槽位内放置有真理水晶时，在基座顶部上方渲染随时间上下悬浮并匀速旋转的水晶。
 */
class TruthCrystalBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context
) : BlockEntityRenderer<TruthCrystalBlockEntity> {

    override fun render(
        be: TruthCrystalBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val stack = be.item
        if (stack.isEmpty || stack.item !is ItemTruthCrystal) return

        val level = be.level ?: return
        val gameTime = level.gameTime + partialTick

        poseStack.pushPose()
        // 移至基座顶部上方中央并随时间微幅上下悬浮
        val hoverOffset = Mth.sin(gameTime * 0.08f) * 0.04f
        poseStack.translate(0.5, 1.1 + hoverOffset, 0.5)

        // 缓慢匀速绕 Y 轴旋转
        val angle = (gameTime * 2.5f) % 360f
        poseStack.mulPose(Axis.YP.rotationDegrees(angle))

        // 缩放大小以适配基座
        poseStack.scale(0.65f, 0.65f, 0.65f)

        Minecraft.getInstance().itemRenderer.renderStatic(
            stack,
            ItemDisplayContext.FIXED,
            packedLight,
            packedOverlay,
            poseStack,
            bufferSource,
            level,
            be.blockPos.hashCode()
        )
        poseStack.popPose()
    }
}
