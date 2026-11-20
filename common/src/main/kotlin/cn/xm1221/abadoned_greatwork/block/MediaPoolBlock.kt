package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.GarbageIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import cn.xm1221.abadoned_greatwork.block.entity.MediaPoolBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.CircleImageMerge
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 媒质池（`media_pool`）— 临时与法术环双向交换媒质的蓄能节点方块（仅支持水平平放）。
 *
 * - **充能**：手持紫水晶粉、碎片、充能紫水晶或造物解除器等媒质源右击即可注入池中（上限 `7680` 粉）；
 * - **波控存取**：媒质波流经本方块时从栈顶弹出一个数值 `n`：
 *   - 栈为空时触发参数不足事故；栈顶非有限数值时触发参数类型事故；
 *   - `n > 0`：从池中取出 `n` 粉注入环的促动石（合流多促动石时平分），池内不足时触发媒质不足事故；
 *   - `n < 0`：从促动石抽取 `|n|` 粉充入池中，促动石总余量不足时触发媒质不足事故；
 *   - `n == 0`：仅消耗该数后继续传递控制流。
 */
class MediaPoolBlock(properties: Properties) : BlockCircleComponent(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(ENERGIZED, false)
        )
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        MediaPoolBlockEntity(Abadoned_greatworkBlockEntities.MEDIA_POOL.value, pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPE

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(ENERGIZED, false)

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int =
        (level.getBlockEntity(pos) as? MediaPoolBlockEntity)?.analogOutputSignal() ?: 0

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult {
        val held = player.getItemInHand(hand)
        if (held.isEmpty) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val be = level.getBlockEntity(pos) as? MediaPoolBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (!be.hasMediaForInsertion(held)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

        if (!level.isClientSide) {
            if (be.insertMediaFromItem(held)) {
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.85f, 1.05f)
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide)
    }

    // ==================== 法术环 ====================

    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = enterDir != Direction.DOWN

    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> =
        EXIT_DIRECTIONS.clone()

    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val stack = imageIn.stack
        if (stack.isEmpty()) {
            triggerMishap(MishapNotEnoughArgs(1, 0), GarbageIota(), imageIn, env, bs)
            return ICircleComponent.ControlFlow.Stop()
        }

        val top = stack.last()
        if (top !is DoubleIota || !top.double.isFinite()) {
            triggerMishap(MishapInvalidIota.ofType(top, 0, "double"), top, imageIn, env, bs)
            return ICircleComponent.ControlFlow.Stop()
        }

        val be = world.getBlockEntity(pos) as? MediaPoolBlockEntity
        val impetuses = collectCircleImpetuses(imageIn, env, world)
        val dust = top.double

        if (dust > 0.0) {
            val amount = toMediaAmount(dust)
            if (amount > 0L && (be == null || !be.dispenseToImpetuses(amount, impetuses))) {
                triggerMishap(MishapNotEnoughMedia(amount), top, imageIn, env, bs)
                return ICircleComponent.ControlFlow.Stop()
            }
            if (amount > 0L) {
                spawnTransferFx(world, pos, 1.35f)
            }
        } else if (dust < 0.0) {
            val amount = toMediaAmount(-dust)
            if (amount > 0L && (be == null || !be.chargeFromImpetuses(amount, impetuses))) {
                triggerMishap(MishapNotEnoughMedia(amount), top, imageIn, env, bs)
                return ICircleComponent.ControlFlow.Stop()
            }
            if (amount > 0L) {
                spawnTransferFx(world, pos, 0.9f)
            }
        }

        val nextImage = imageIn.copy(stack = stack.dropRight(1))
        val exits = possibleExitDirections(pos, bs, world)
            .filter { it != enterDir.opposite }
            .map { exitPositionFromDirection(pos, it) }
        return ICircleComponent.ControlFlow.Continue(nextImage, exits)
    }

    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        Direction.UP

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f

    private fun collectCircleImpetuses(
        image: CastingImage,
        env: CircleCastEnv,
        world: ServerLevel,
    ): List<BlockEntityAbstractImpetus> {
        val result = LinkedHashSet<BlockEntityAbstractImpetus>()
        env.impetus?.let(result::add)
        for (impPos in CircleImageMerge.extractImpetusPositions(image, env.circleState().impetusPos)) {
            (world.getBlockEntity(impPos) as? BlockEntityAbstractImpetus)?.let(result::add)
        }
        return result.toList()
    }

    private fun triggerMishap(
        mishap: Mishap,
        culprit: Iota,
        image: CastingImage,
        env: CircleCastEnv,
        bs: BlockState,
    ) {
        val ctx = Mishap.Context(null, bs.block.name)
        val effect = OperatorSideEffect.DoMishap(mishap, ctx)
        effect.performEffect(CastingVM(image, env))
        env.postExecution(
            CastResult(
                culprit,
                SpellContinuation.Done,
                image,
                listOf(effect),
                mishap.resolutionType(env),
                HexEvalSounds.MISHAP.get(),
            )
        )
    }

    private fun spawnTransferFx(world: ServerLevel, pos: BlockPos, pitch: Float) {
        val fxPos = Vec3.atCenterOf(pos).add(0.0, 0.35, 0.0)
        world.sendParticles(ParticleTypes.WITCH, fxPos.x, fxPos.y, fxPos.z, 6, 0.22, 0.12, 0.22, 0.02)
        world.playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_HIT, SoundSource.BLOCKS, 0.45f, pitch)
    }

    companion object {
        private val EXIT_DIRECTIONS: EnumSet<Direction> = EnumSet.complementOf(EnumSet.of(Direction.UP))

        private val SHAPE: VoxelShape = Shapes.or(
            box(0.0, 0.0, 0.0, 16.0, 3.0, 16.0),
            box(1.0, 3.0, 1.0, 15.0, 14.0, 15.0),
        )

        private fun toMediaAmount(dust: Double): Long {
            val scaled = dust * MediaConstants.DUST_UNIT.toDouble()
            if (!scaled.isFinite() || scaled >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
            return max(0L, scaled.roundToLong())
        }
    }
}
