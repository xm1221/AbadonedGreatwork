package cn.xm1221.abadoned_greatwork.block

import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessChannelState
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * 道引标（`ambit_anchor`）— 在世界中界定施法域轮廓顶点与上下界高度的独立界桩方块。
 *
 * - 放下时默认顺位为 `0`（未指定），持有经 [AmbitSwitchBlock] 刷印过频道的物品放下时自动继承频道；
 * - **空手右击**：切换频道 `0..15`（保留自身顺位号）；
 * - **潜行 + 空手右击**：手动递增这根道引标的顺位（`0 -> 1 -> 2 -> ... -> 16 -> 1`）。
 */
class AmbitAnchorBlock(properties: Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AmbitAnchorBlockEntity(Abadoned_greatworkBlockEntities.AMBIT_ANCHOR.value, pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPE

    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        stack: ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel) return
        val be = level.getBlockEntity(pos) as? AmbitAnchorBlockEntity ?: return

        be.applyPlacedBinding(BlockItem.getBlockEntityData(stack))
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!player.getItemInHand(hand).isEmpty) return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? AmbitAnchorBlockEntity ?: return InteractionResult.PASS

        if (!level.isClientSide) {
            val srv = level as ServerLevel
            if (player.isShiftKeyDown) {
                val newOrder = be.cycleManualOrder()
                srv.playSound(
                    null,
                    pos,
                    SoundEvents.AMETHYST_CLUSTER_PLACE,
                    SoundSource.BLOCKS,
                    0.85f,
                    0.75f + (newOrder % 16) * 0.05f,
                )
            } else {
                val ch = be.cycleChannel(1)
                srv.playSound(
                    null,
                    pos,
                    SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.BLOCKS,
                    1.0f,
                    0.7f + ch * 0.05f,
                )
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        level: BlockGetter?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        super.appendHoverText(stack, level, tooltip, flag)
        val tag = BlockItem.getBlockEntityData(stack) ?: return
        val probe = WirelessChannelState {}
        probe.load(tag)
        tooltip.add(
            Component.translatable(
                "text.abadoned_greatwork.wireless.channel",
                probe.channelDisplay(),
            ).withStyle(ChatFormatting.GRAY)
        )
    }

    companion object {
        private val SHAPE: VoxelShape = Shapes.or(
            box(2.0, 0.0, 2.0, 14.0, 4.5, 14.0),
            box(4.5, 4.5, 4.5, 11.5, 16.0, 11.5),
        )
    }
}
