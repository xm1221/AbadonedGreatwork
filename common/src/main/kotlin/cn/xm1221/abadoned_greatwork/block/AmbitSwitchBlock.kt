package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import cn.xm1221.abadoned_greatwork.block.entity.AmbitSwitchBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitNetwork
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitPolygonShape
import cn.xm1221.abadoned_greatwork.casting.circles.AmbitReshapeHook
import cn.xm1221.abadoned_greatwork.casting.circles.MishapAmbitBudgetExceeded
import cn.xm1221.abadoned_greatwork.casting.circles.MishapNotEnoughAmbitAnchors
import cn.xm1221.abadoned_greatwork.casting.circles.triggerInCircle
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumSet

/**
 * 范围切换环件（`ambit_switch`）— 串联在法术环路中的“柳叶笔域形切换石”。
 *
 * - 手持 `ambit_anchor` 物品右击本方块：将本方块的频道刷印到手中整组锚点物品上；
 * - 空手右击（潜行反向）：循环切换本方块的频道 `0..15`（亦支持通过焦点写入任意 Iota）；
 * - 媒质波流经时，读取该频道所有锚点闭合成的“奇偶规则多边形柱体”，在体积不超过原法阵方盒预算的前提下
 *   替换当前分支的施法范围（空频道 / `0` 锚点则还原为原版默认方盒；`1..2` 锚点或超出预算则触发事故）。
 */
class AmbitSwitchBlock(properties: Properties) : BlockCircleComponent(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(ENERGIZED, false)
                .setValue(FACING, Direction.UP)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(FACING)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AmbitSwitchBlockEntity(Abadoned_greatworkBlockEntities.AMBIT_SWITCH.value, pos, state)

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = Shapes.block()

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val base = context.nearestLookingDirection.opposite
        val facing = if (context.player?.isShiftKeyDown == true) base.opposite else base
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(ENERGIZED, false)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        val be = level.getBlockEntity(pos) as? AmbitSwitchBlockEntity ?: return InteractionResult.PASS

        if (held.item === Abadoned_greatworkItems.AMBIT_ANCHOR.value) {
            if (!level.isClientSide) {
                val tag = BlockItem.getBlockEntityData(held)?.copy() ?: CompoundTag()
                be.channelState.copyTo(tag)
                BlockItem.setBlockEntityData(held, Abadoned_greatworkBlockEntities.AMBIT_ANCHOR.value, tag)
                player.displayClientMessage(
                    Component.translatable(
                        "text.abadoned_greatwork.ambit.bound_item",
                        be.channelState.channelDisplay(),
                    ),
                    true,
                )
                level.playSound(
                    null,
                    pos,
                    SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.BLOCKS,
                    0.9f,
                    1.25f,
                )
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        if (!held.isEmpty) return InteractionResult.PASS

        if (!level.isClientSide) {
            val delta = if (player.isShiftKeyDown) -1 else 1
            val ch = be.channelState.cycleChannel(delta)
            player.displayClientMessage(
                Component.translatable(
                    "text.abadoned_greatwork.wireless.channel",
                    be.channelState.channelDisplay(),
                ),
                true,
            )
            level.playSound(
                null,
                pos,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS,
                1.0f,
                0.7f + ch * 0.05f,
            )
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // ==================== 法术环 ====================

    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = true

    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> =
        EnumSet.allOf(Direction::class.java)

    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val be = world.getBlockEntity(pos) as? AmbitSwitchBlockEntity
        var nextUserData = imageIn.userData

        if (be != null) {
            val key = be.channelState.key()
            val anchors = AmbitNetwork.getOrderedAnchors(world, key)
            val budgetVolume = AmbitReshapeHook.circleBudgetVolume(env.circleState().bounds)
            when (val res = AmbitPolygonShape.computeAndValidate(anchors, budgetVolume)) {
                AmbitPolygonShape.ValidationResult.Empty -> {
                    be.recordShapeSummary(0L, 0, 0L)
                    nextUserData = AmbitReshapeHook.clearShapeFromUserData(nextUserData)
                }

                is AmbitPolygonShape.ValidationResult.TooFewAnchors -> {
                    MishapNotEnoughAmbitAnchors(be.channelState.channelDisplay(), res.count)
                        .triggerInCircle(imageIn, env, bs)
                    return ICircleComponent.ControlFlow.Stop()
                }

                is AmbitPolygonShape.ValidationResult.OverBudget -> {
                    MishapAmbitBudgetExceeded(
                        neededVolume = res.neededVolume,
                        budgetVolume = res.budgetVolume,
                        areaBlocks = res.areaBlocks,
                        height = res.height,
                    ).triggerInCircle(imageIn, env, bs)
                    return ICircleComponent.ControlFlow.Stop()
                }

                is AmbitPolygonShape.ValidationResult.Valid -> {
                    val shape = res.shape
                    be.recordShapeSummary(shape.areaBlocks, shape.height, shape.volumeBlocks)
                    nextUserData = AmbitReshapeHook.writeShapeToUserData(nextUserData, shape)
                    AmbitNetwork.spawnPolygonOutlineParticles(world, anchors)
                }
            }
        }

        val nextImage = if (nextUserData === imageIn.userData) {
            imageIn
        } else {
            imageIn.copy(userData = nextUserData)
        }
        val exits = possibleExitDirections(pos, bs, world)
            .filter { it != enterDir.opposite }
            .map { exitPositionFromDirection(pos, it) }
        return ICircleComponent.ControlFlow.Continue(nextImage, exits)
    }

    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        Direction.UP

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING
    }
}
