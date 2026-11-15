package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import cn.xm1221.abadoned_greatwork.block.entity.WaveRepeaterBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessWaveNetwork
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumMap
import java.util.EnumSet

/**
 * 号角中继器（`wave_repeater`）— 无线媒质波中继站与法术环增程发信节点。
 *
 * - **纯无线接力**：置于野外即可监听同频发射端或上游中继器发来的无线媒质波，
 *   经短暂谐振延迟后沿号角前向增益辐射到更远处的接收石与下一跳中继器，支持多跳接力与防回环；
 * - **法术环串联**：也可接入石板路径，在媒质波穿过时发起增强无线广播并继续向下游传递。
 */
class WaveRepeaterBlock(properties: Properties) : BlockCircleComponent(properties), EntityBlock {

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
        builder.add(FACING, HALF)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WaveRepeaterBlockEntity(Abadoned_greatworkBlockEntities.WAVE_REPEATER.value, pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

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

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val clicked = context.clickedFace
        val half = when {
            clicked == Direction.DOWN -> Half.TOP
            clicked == Direction.UP -> Half.BOTTOM
            context.clickLocation.y - context.clickedPos.y > 0.5 -> Half.TOP
            else -> Half.BOTTOM
        }
        val sneak = context.player?.isShiftKeyDown == true
        val facing = if (sneak) context.horizontalDirection.opposite else context.horizontalDirection
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(HALF, half)
            .setValue(ENERGIZED, false)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** 空手右击 / 潜行右击循环切换谐振频道 `0..15`。 */
    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!player.getItemInHand(hand).isEmpty) return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? WaveRepeaterBlockEntity ?: return InteractionResult.PASS

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

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val be = level.getBlockEntity(pos) as? WaveRepeaterBlockEntity ?: return
        be.onScheduledTick(level, state)
    }

    // ==================== 法术环 ====================

    override fun startEnergized(pos: BlockPos, bs: BlockState, level: Level): BlockState {
        (level.getBlockEntity(pos) as? WaveRepeaterBlockEntity)?.clearWirelessGlowTimer()
        return super.startEnergized(pos, bs, level)
    }

    override fun endEnergized(pos: BlockPos, bs: BlockState, level: Level): BlockState {
        (level.getBlockEntity(pos) as? WaveRepeaterBlockEntity)?.clearWirelessGlowTimer()
        return super.endEnergized(pos, bs, level)
    }

    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = enterDir != attachNormal(bs).opposite

    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> =
        EnumSet.complementOf(EnumSet.of(attachNormal(bs)))

    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val be = world.getBlockEntity(pos) as? WaveRepeaterBlockEntity
        if (be != null) {
            val result = WirelessWaveNetwork.emitFromRepeater(
                level = world,
                txPos = pos,
                antennaAxisDir = attachNormal(bs),
                hornForwardDir = bs.getValue(FACING),
                txChannel = be.channelState.key(),
                image = imageIn,
                caster = env.castingEntity as? ServerPlayer,
                slatesPassed = env.circleState().reachedSlate.coerceAtLeast(1L),
                hopsLeft = WirelessWaveNetwork.MAX_REPEATER_HOPS,
                visitedNodes = setOf(pos.immutable()),
            )
            be.recordRelayResult(result)
        }

        val exits = possibleExitDirections(pos, bs, world)
            .filter { it != enterDir.opposite }
            .map { exitPositionFromDirection(pos, it) }
        return ICircleComponent.ControlFlow.Continue(imageIn, exits)
    }

    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        attachNormal(bs)

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
        val HALF: EnumProperty<Half> = BlockStateProperties.HALF

        fun attachNormal(state: BlockState): Direction =
            if (state.getValue(HALF) == Half.TOP) Direction.DOWN else Direction.UP

        private fun modelAngles(facing: Direction, half: Half): Pair<Int, Int> = when (half) {
            Half.BOTTOM -> when (facing) {
                Direction.NORTH -> 0 to 0
                Direction.EAST -> 0 to 90
                Direction.SOUTH -> 0 to 180
                Direction.WEST -> 0 to 270
                else -> 0 to 0
            }
            Half.TOP -> when (facing) {
                Direction.NORTH -> 180 to 180
                Direction.EAST -> 180 to 270
                Direction.SOUTH -> 180 to 0
                Direction.WEST -> 180 to 90
                else -> 180 to 180
            }
        }

        private val SHAPES: Map<Half, Map<Direction, VoxelShape>> = mapOf(
            Half.BOTTOM to EnumMap<Direction, VoxelShape>(Direction::class.java),
            Half.TOP to EnumMap<Direction, VoxelShape>(Direction::class.java),
        ).apply {
            for (half in Half.entries) {
                val map = getValue(half) as EnumMap<Direction, VoxelShape>
                for (dir in Direction.Plane.HORIZONTAL) {
                    val (rx, ry) = modelAngles(dir, half)
                    map[dir] = WirelessWaveNetwork.buildOrientedBoxesXY(
                        rx,
                        ry,
                        doubleArrayOf(0.0, 0.0, 0.0, 16.0, 3.0, 16.0),
                        doubleArrayOf(4.0, 3.0, 4.0, 12.0, 6.0, 12.0),
                        doubleArrayOf(2.5, 5.0, 0.0, 13.5, 16.0, 13.0),
                        doubleArrayOf(4.0, 5.5, 8.0, 5.0, 16.0, 9.0),
                    )
                }
            }
        }
    }
}
