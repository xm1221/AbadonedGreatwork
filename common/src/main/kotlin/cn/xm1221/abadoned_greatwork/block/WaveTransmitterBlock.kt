package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import cn.xm1221.abadoned_greatwork.block.entity.WaveTransmitterBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.WirelessWaveNetwork
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
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
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumMap
import java.util.EnumSet
import kotlin.math.abs

/**
 * 队长发信石（`wave_transmitter`）— 旗帜天线与励磁线圈发射环件。
 *
 * 作为发信法术环的串联节点：媒质波经底座穿过时，向外广播同频且极化匹配的无线媒质脉冲，
 * 同时继续沿发信环本身向下游传递，完成闭环。
 */
class WaveTransmitterBlock(properties: Properties) : BlockCircleComponent(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(ENERGIZED, false)
                .setValue(FACING, Direction.UP)
                .setValue(FLAG_ROT, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(FACING, FLAG_ROT)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WaveTransmitterBlockEntity(Abadoned_greatworkBlockEntities.WAVE_TRANSMITTER.value, pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPES.getValue(state.getValue(FACING))

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // 六向底座朝向（类似活塞/促动石，Shift 可反向） + 旗帜绕轴四向朝向
        val base = context.nearestLookingDirection.opposite
        val facing = if (context.player?.isShiftKeyDown == true) base.opposite else base
        val desiredFront = resolveDesiredFlagFront(context, facing)
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(FLAG_ROT, flagRotForFront(facing, desiredFront))
            .setValue(ENERGIZED, false)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState {
        val facing = state.getValue(FACING)
        val front = flagFrontDirection(facing, state.getValue(FLAG_ROT))
        val newFacing = rotation.rotate(facing)
        val newFront = rotation.rotate(front)
        return state
            .setValue(FACING, newFacing)
            .setValue(FLAG_ROT, flagRotForFront(newFacing, newFront))
    }

    override fun mirror(state: BlockState, mirror: Mirror): BlockState {
        val facing = state.getValue(FACING)
        val front = flagFrontDirection(facing, state.getValue(FLAG_ROT))
        val newFacing = mirror.mirror(facing)
        val newFront = mirror.mirror(front)
        return state
            .setValue(FACING, newFacing)
            .setValue(FLAG_ROT, flagRotForFront(newFacing, newFront))
    }

    /** 空手右键/潜行右键循环切换频道 `0..15`。 */
    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!player.getItemInHand(hand).isEmpty) return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? WaveTransmitterBlockEntity ?: return InteractionResult.PASS

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

    // ==================== 法术环 ====================

    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = enterDir != bs.getValue(FACING).opposite

    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> =
        EnumSet.complementOf(EnumSet.of(bs.getValue(FACING)))

    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val be = world.getBlockEntity(pos) as? WaveTransmitterBlockEntity
        if (be != null) {
            val result = WirelessWaveNetwork.emitWave(
                level = world,
                txPos = pos,
                txState = bs,
                txChannel = be.channelState.key(),
                image = imageIn,
                env = env,
            )
            be.recordTransmitResult(result)
        }

        val exits = possibleExitDirections(pos, bs, world)
            .filter { it != enterDir.opposite }
            .map { exitPositionFromDirection(pos, it) }
        return ICircleComponent.ControlFlow.Continue(imageIn, exits)
    }

    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        bs.getValue(FACING)

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING
        val FLAG_ROT: IntegerProperty = IntegerProperty.create("flag_rot", 0, 3)

        /** 每种底座朝向下，`flag_rot = 0..3` 时旗帜正面指向的世界方向表（与 BER 旋转矩阵严格一致）。 */
        private val FLAG_FRONTS: Map<Direction, Array<Direction>> = EnumMap<Direction, Array<Direction>>(Direction::class.java).apply {
            put(Direction.UP, arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST))
            put(Direction.DOWN, arrayOf(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST))
            put(Direction.NORTH, arrayOf(Direction.DOWN, Direction.EAST, Direction.UP, Direction.WEST))
            put(Direction.EAST, arrayOf(Direction.DOWN, Direction.SOUTH, Direction.UP, Direction.NORTH))
            put(Direction.SOUTH, arrayOf(Direction.DOWN, Direction.WEST, Direction.UP, Direction.EAST))
            put(Direction.WEST, arrayOf(Direction.DOWN, Direction.NORTH, Direction.UP, Direction.SOUTH))
        }

        fun flagFrontDirection(facing: Direction, flagRot: Int): Direction =
            FLAG_FRONTS.getValue(facing)[flagRot and 3]

        fun flagRotForFront(facing: Direction, front: Direction): Int {
            val idx = FLAG_FRONTS.getValue(facing).indexOf(front)
            return if (idx >= 0) idx else 0
        }

        private fun resolveDesiredFlagFront(context: BlockPlaceContext, facing: Direction): Direction {
            if (facing.axis.isVertical) {
                return context.horizontalDirection.opposite
            }
            if (context.clickedFace.axis == facing.axis) {
                val off = context.clickLocation.subtract(Vec3.atCenterOf(context.clickedPos))
                val horizIsX = facing.axis == Direction.Axis.Z
                val horizOff = if (horizIsX) off.x else off.z
                if (abs(off.y) >= abs(horizOff) && abs(off.y) > 0.16) {
                    return if (off.y > 0.0) Direction.UP else Direction.DOWN
                }
                if (abs(horizOff) > abs(off.y) && abs(horizOff) > 0.16) {
                    return if (horizIsX) {
                        if (horizOff > 0.0) Direction.EAST else Direction.WEST
                    } else {
                        if (horizOff > 0.0) Direction.SOUTH else Direction.NORTH
                    }
                }
                return Direction.DOWN
            }
            val horiz = context.horizontalDirection.opposite
            return if (horiz.axis != facing.axis) horiz else Direction.DOWN
        }

        private val SHAPES: Map<Direction, VoxelShape> = EnumMap<Direction, VoxelShape>(Direction::class.java).apply {
            for (dir in Direction.entries) {
                put(
                    dir,
                    WirelessWaveNetwork.buildOrientedBoxes(
                        dir,
                        doubleArrayOf(0.0, 0.0, 0.0, 16.0, 3.0, 16.0),
                        doubleArrayOf(4.0, 3.0, 4.0, 12.0, 10.0, 12.0),
                        doubleArrayOf(6.0, 10.0, 6.0, 10.0, 16.0, 10.0),
                    ),
                )
            }
        }
    }
}
