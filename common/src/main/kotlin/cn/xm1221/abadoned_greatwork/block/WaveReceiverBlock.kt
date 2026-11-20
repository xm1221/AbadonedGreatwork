package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockAbstractImpetus
import at.petrak.hexcasting.common.lib.HexItems
import cn.xm1221.abadoned_greatwork.block.entity.WaveReceiverBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessWaveNetwork
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumMap
import kotlin.math.abs

/**
 * 谐振收信石（`wave_receiver`）— 感应线圈与双极子接收促动环件。
 *
 * 继承 [BlockAbstractImpetus]：
 * - 方块正前方感应筒上的**徽记朝向**直接就是 `FACING`（即起振脉冲的释放方向），
 *   并由 `HALF` 区分贴地 / 倒挂天花板或贴墙方向；
 * - 收到同频无线媒质波时从徽记那一侧向前发送媒质脉冲，且不要求路径闭合回自身。
 */
class WaveReceiverBlock(properties: Properties) : BlockAbstractImpetus(properties) {

    override fun getBlockEntityType() =
        Abadoned_greatworkBlockEntities.WAVE_RECEIVER.value as net.minecraft.world.level.block.entity.BlockEntityType<out at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus>

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(ENERGIZED, false)
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, Half.BOTTOM)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(HALF)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WaveReceiverBlockEntity(Abadoned_greatworkBlockEntities.WAVE_RECEIVER.value, pos, state)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val clicked = context.clickedFace
        val sneak = context.player?.isShiftKeyDown == true
        val lookY = context.player?.lookAngle?.y ?: 0.0
        val horiz = if (sneak) context.horizontalDirection.opposite else context.horizontalDirection

        return when {
            clicked == Direction.UP -> defaultBlockState()
                .setValue(FACING, horiz)
                .setValue(HALF, Half.BOTTOM)
            clicked == Direction.DOWN -> defaultBlockState()
                .setValue(FACING, horiz)
                .setValue(HALF, Half.TOP)
            abs(lookY) > 0.6 -> {
                val vert = if ((lookY > 0.0) xor sneak) Direction.UP else Direction.DOWN
                val half = when {
                    vert == Direction.UP && clicked == Direction.NORTH -> Half.TOP
                    vert == Direction.DOWN && clicked == Direction.SOUTH -> Half.TOP
                    else -> Half.BOTTOM
                }
                defaultBlockState().setValue(FACING, vert).setValue(HALF, half)
            }
            else -> {
                val half = if (context.clickLocation.y - context.clickedPos.y > 0.5) Half.TOP else Half.BOTTOM
                defaultBlockState().setValue(FACING, horiz).setValue(HALF, half)
            }
        }
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState {
        val facing = state.getValue(FACING)
        val half = state.getValue(HALF)
        if (facing.axis.isVertical) {
            val flipped = if (rotation == Rotation.CLOCKWISE_180) {
                if (half == Half.BOTTOM) Half.TOP else Half.BOTTOM
            } else {
                half
            }
            return state.setValue(HALF, flipped)
        }
        return state.setValue(FACING, rotation.rotate(facing))
    }

    override fun mirror(state: BlockState, mirror: Mirror): BlockState {
        val facing = state.getValue(FACING)
        if (facing.axis.isVertical) {
            return if (mirror == Mirror.LEFT_RIGHT) {
                val half = state.getValue(HALF)
                state.setValue(HALF, if (half == Half.BOTTOM) Half.TOP else Half.BOTTOM)
            } else {
                state
            }
        }
        return state.rotate(mirror.getRotation(facing))
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape {
        val half = state.getValue(HALF)
        val facing = state.getValue(FACING)
        return SHAPES[half]?.get(facing) ?: SHAPES.getValue(Half.BOTTOM).getValue(Direction.NORTH)
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        val be = level.getBlockEntity(pos) as? WaveReceiverBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)

        // 1. 手持媒质物品右击：向促动石媒质槽充能
        if (!held.isEmpty) {
            val isMediaItem = held.`is`(HexItems.CREATIVE_UNLOCKER.get()) ||
                be.extractMediaFromInsertedItem(held, true) > 0L
            if (!isMediaItem) return InteractionResult.PASS

            if (!level.isClientSide) {
                be.insertMedia(held)
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.8f, 1.1f)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }

        // 2. 空手右击 / 潜行右击：切换谐振频道 0..15
        if (!level.isClientSide) {
            val delta = if (player.isShiftKeyDown) -1 else 1
            val ch = be.channelState.cycleChannel(delta)
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

    companion object {
        val HALF: EnumProperty<Half> = BlockStateProperties.HALF

        private fun modelAngles(facing: Direction, half: Half): Pair<Int, Int> = when (half) {
            Half.BOTTOM -> when (facing) {
                Direction.NORTH -> 0 to 0
                Direction.EAST -> 0 to 90
                Direction.SOUTH -> 0 to 180
                Direction.WEST -> 0 to 270
                Direction.UP -> 270 to 0
                Direction.DOWN -> 90 to 0
            }
            Half.TOP -> when (facing) {
                Direction.NORTH -> 180 to 180
                Direction.EAST -> 180 to 270
                Direction.SOUTH -> 180 to 0
                Direction.WEST -> 180 to 90
                Direction.UP -> 270 to 180
                Direction.DOWN -> 90 to 180
            }
        }

        private val SHAPES: Map<Half, Map<Direction, VoxelShape>> = mapOf(
            Half.BOTTOM to EnumMap<Direction, VoxelShape>(Direction::class.java),
            Half.TOP to EnumMap<Direction, VoxelShape>(Direction::class.java),
        ).apply {
            for (half in Half.entries) {
                val map = getValue(half) as EnumMap<Direction, VoxelShape>
                for (dir in Direction.entries) {
                    val (rx, ry) = modelAngles(dir, half)
                    map[dir] = WirelessWaveNetwork.buildOrientedBoxesXY(
                        rx,
                        ry,
                        doubleArrayOf(0.0, 0.0, 0.0, 16.0, 3.0, 16.0),
                        doubleArrayOf(4.0, 3.0, 4.0, 12.0, 10.0, 12.0),
                        doubleArrayOf(2.0, 5.0, 7.0, 4.0, 16.0, 9.0),
                        doubleArrayOf(12.0, 5.0, 7.0, 14.0, 16.0, 9.0),
                    )
                }
            }
        }
    }
}
