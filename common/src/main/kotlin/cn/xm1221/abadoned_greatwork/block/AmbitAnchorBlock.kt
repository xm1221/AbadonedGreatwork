package cn.xm1221.abadoned_greatwork.block

import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitNetwork
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
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * 范围锚点（`ambit_anchor`）— 在世界中界定施法域轮廓顶点与上下界高度的独立锚桩方块。
 *
 * - 持有经由 [AmbitSwitchBlock] 刷印过频道的物品放下时，自动继承频道并按放置次序追加为该频道末位节点；
 * - 空手右击：切换频道 `0..15` 并置入新频道末尾；
 * - **潜行 + 空手右击**：将该锚点在本频道内的顺位序号向后挪动一位（末尾回绕首位），并喷射轮廓连线粒子提示。
 */
class AmbitAnchorBlock(properties: Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AmbitAnchorBlockEntity(Abadoned_greatworkBlockEntities.AMBIT_ANCHOR.value, pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

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
        val srv = level as? ServerLevel ?: return
        val be = srv.getBlockEntity(pos) as? AmbitAnchorBlockEntity ?: return

        val itemTag = BlockItem.getBlockEntityData(stack)
        if (itemTag != null) {
            // 先预览加载目标频道，以便分配正确频道桶中的顺位号
            val probe = WirelessChannelState {}
            probe.load(itemTag)
            val allocated = AmbitNetwork.allocateAppendOrder(srv, probe.key())
            be.applyPlacedBinding(itemTag, allocated)
        } else {
            val allocated = AmbitNetwork.allocateAppendOrder(srv, be.channelState.key())
            be.applyPlacedBinding(null, allocated)
        }

        val (rank, total) = AmbitNetwork.getAnchorRank(srv, pos, be.channelState.key())
        (placer as? Player)?.displayClientMessage(
            Component.translatable(
                "text.abadoned_greatwork.ambit.anchor_placed",
                be.channelState.channelDisplay(),
                rank,
                total,
            ),
            true,
        )
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
                val (newRank, total) = AmbitNetwork.rotateAnchorOrder(srv, pos, be.channelState.key())
                val anchors = AmbitNetwork.getOrderedAnchors(srv, be.channelState.key())
                AmbitNetwork.spawnPolygonOutlineParticles(srv, anchors)
                player.displayClientMessage(
                    Component.translatable(
                        "text.abadoned_greatwork.ambit.anchor_reordered",
                        be.channelState.channelDisplay(),
                        newRank,
                        total,
                    ),
                    true,
                )
                srv.playSound(
                    null,
                    pos,
                    SoundEvents.AMETHYST_CLUSTER_PLACE,
                    SoundSource.BLOCKS,
                    0.85f,
                    0.8f + (newRank % 8) * 0.08f,
                )
            } else {
                val pair = be.reassignChannelAndOrder(1)
                player.displayClientMessage(
                    Component.translatable(
                        "text.abadoned_greatwork.ambit.anchor_placed",
                        be.channelState.channelDisplay(),
                        pair.first,
                        pair.second,
                    ),
                    true,
                )
                srv.playSound(
                    null,
                    pos,
                    SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.BLOCKS,
                    1.0f,
                    0.7f + be.channelState.channel * 0.05f,
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
        private val SHAPE: VoxelShape = box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0)
    }
}
