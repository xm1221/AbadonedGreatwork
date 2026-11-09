package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.common.blocks.circles.BlockSlate
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.levelgen.SurfaceRules.state
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumSet

/**
 *
 * ## 状态
 * - `facing`：竖墙贴在哪一面（= 竖墙外侧法线方向），4 个水平方向
 * - `half=bottom`：板贴地板、竖墙向上（模型不翻转）
 * - `half=top`：板贴天花板、竖墙垂下（模型绕 X 翻转 180°）
 * - `energized`：由 [BlockCircleComponent] 提供；媒质波经过时为 `true`，比较器输出 15
 *
 * ## 放置规则
 * - 点方块的侧面：竖墙贴着被点的那一格（`facing` = 被点面的反方向）
 * - 点方块的顶面/底面：竖墙朝着玩家视线方向；点底面时板贴天花板
 *
 * ## 形状
 * 8 个碰撞/选取形状全部由模型元素逐坐标换算，**没有任何整格兜底**：
 *
 * ```
 * 底板  ( 0, 0, 0)-(16, 1,16)   贴地
 * 底板  ( 0,15, 0)-(16,16,16)   贴天花板（上面的底板绕 X 翻 180°）
 * 竖墙  x 0..1 / z 0..16        贴地时 y 1..16，贴天花板时 y 0..15
 * ```
 */
class PlugSlateBlock(properties: Properties) : BlockCircleComponent(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(ENERGIZED, false)
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, Half.BOTTOM),
        )
    }

    // ==================== 状态 ====================

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(FACING, HALF)
    }

    // ==================== 放置 ====================

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val clicked = context.clickedFace
        val half = if (clicked == Direction.DOWN) Half.TOP else Half.BOTTOM
        val facing = if (clicked.axis.isHorizontal) clicked.opposite else context.horizontalDirection
        return defaultBlockState().setValue(FACING, facing).setValue(HALF, half)
    }

    // ==================== 结构方块旋转 ====================

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    // ==================== 形状 ====================

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape {
        val half = state.getValue(HALF)
        val facing = state.getValue(FACING)
        // 兜底只给底板，绝不返回 Shapes.block()
        return SHAPES[half]?.get(facing) ?: PLATES.getValue(half)
    }

    // ==================== 法术环 ====================

    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        if (bs.getValue(HALF) == Half.BOTTOM) Direction.UP else Direction.DOWN

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f - 15f / 16f

    /**
     * 建环洪泛时扩散的候选方向：**全部 6 个**。
     *
     * 洪泛时还不知道波会贴哪一片（取决于来向），所以取两片面内方向的并集 ——
     * 横片给 `x/z` 四向，竖片给 `y` 两向加折线两向，合起来就是 6 个。
     * 真正的收窄在 [acceptControlFlow] 里按「波贴着哪一轴」做。
     */
    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> =
        EnumSet.allOf(Direction::class.java)

    /**
     * **一片 = 薄轴 + 贴合面。**
     *
     * 上游石板一行 `enterDir != normal.opposite` 就够，是因为它一片只有一个法线，`enterDir`
     * 和法线的关系就能定「波在片的哪一侧」。本件两片法线垂直，同一个 `enterDir` 可能是撞板、
     * 也可能是沿墙 —— 差的那半个自由度是**片贴在哪一面**，用一个轴集合表达不出来。
     *
     * 判据两条，全部只看状态量，不算坐标：
     * 1. 薄轴 == `enterDir.axis` 的片两边都不参与。波只贴在**暴露面**上走，沿薄轴方向它
     *    离不开那个面，也就到不了共享面；`BlockSlate` 靠「入口禁撞面 + 出口禁离面」把这件
     *    事拆给两格各判一半，我们这里是单侧完整判断，所以两侧都要排掉。
     * 2. 剩下的片比贴合面：薄轴不同则必相交（各自满格的那一轴覆盖对方的 1px 带）；
     *    薄轴相同则要求贴同一面（`g` 相同）。
     *
     * 来源不是本件也不是上游 [BlockSlate]（促动石、导向石、别的环件）时取宽。
     */
    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean {
        val src = slabsAt(level.getBlockState(pos.relative(enterDir.opposite))) ?: return true
        val axis = enterDir.axis
        val mine = slabsOf(bs)
        return src.any { s -> s.t != axis && mine.any { m -> meets(s, m, axis) } }
    }

    // ==================== 片 ====================

    /**
     * 一片：薄轴 [t] + 贴合面朝向 [g]（片贴住的那一面，`g.axis == t`）。
     *
     * 暴露面朝 `g.opposite`，所以「贴在哪一面」是一个方向而不是一根轴 —— 轴集合丢掉的
     * 正是这半个自由度：同样含 `Y` 轴的两片，可能一个贴地、一个贴天花板。
     */
    data class Slab(val t: Direction.Axis, val g: Direction)

    /** 本件两片：底板（贴地 / 贴顶）+ 竖墙（贴 `FACING` 那一侧）。 */
    fun slabsOf(bs: BlockState): List<Slab> = listOf(
        Slab(Direction.Axis.Y, if (bs.getValue(HALF) == Half.BOTTOM) Direction.DOWN else Direction.UP),
        Slab(bs.getValue(FACING).axis, bs.getValue(FACING)),
    )

    /**
     * 某格环件的片；`null` = 不认识的方块，调用方取宽。
     *
     * 上游 [BlockSlate] 只有一片，而且它的 `FACING` 是**暴露面朝向**（`AABB_EAST_WALL` 是
     * `x 0..1`，板贴西侧、面朝东），所以贴合面要取 `FACING.opposite` —— 与本件 `FACING`
     * 语义相反：同一个摆位本件写 `WEST`、上游写 `EAST`。
     */
    fun slabsAt(state: BlockState): List<Slab>? =
        when (state.block) {
            is PlugSlateBlock -> slabsOf(state)
            is BlockSlate -> when (state.getValue(BlockSlate.ATTACH_FACE)) {
                AttachFace.FLOOR -> listOf(Slab(Direction.Axis.Y, Direction.DOWN))
                AttachFace.CEILING -> listOf(Slab(Direction.Axis.Y, Direction.UP))
                AttachFace.WALL -> {
                    val facing = state.getValue(BlockSlate.FACING)
                    listOf(Slab(facing.axis, facing.opposite))
                }
                // ATTACH_FACE 三值已穷尽，给个不可达兜底消 Java 枚举可空警告
                else -> null
            }
            else -> null
        }

    /** 来源片 [s] 与本件片 [m] 在同一面格边界上是否相通。 */
    private fun meets(s: Slab, m: Slab, axis: Direction.Axis): Boolean {
        if (m.t == axis) return false                 // 本件片被波沿薄轴穿过，接不住
        return s.t != m.t || s.g == m.g               // 薄轴不同必相交；同轴要比贴合面
    }

    /**
     * 媒质波走到这一格：把镜像原样传下去，只负责转向。
     *
     * 照 [BlockSlate] 的办法，每片只禁**自己暴露面法线**那一向（`allDirs.remove(normal)`），
     * 波贴着的每片各给各的，取**并集**：单片承载时剩 5 向，两片都贴时两片的 5 向互不包含，
     * 并集是 6 向 —— 转角在设定上就是两块板，波在棱上既可以继续沿这一块、也可以转上另一块。
     *
     * 撞面方向（`normal.opposite`）不禁：石板也不禁，交给下一格的 `canEnterFromDirection`
     * 挡。候选里若有多个下一格都愿意接，`CircleExecutionState.tick` 会自己报 `many_exits`
     * —— 那是引擎的权威判断（它真的逐格去问了），这里只负责把候选给全，不替它收窄。
     */
    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val carrying = carryingSlabs(enterDir, pos, bs, world)
        val exits = EnumSet.allOf(Direction::class.java)
        exits.removeIf { exit -> carrying.all { it.g.opposite == exit } }   // 每片只禁离开自己的面
        exits.remove(enterDir.opposite)                                      // 不许原路返回
        return ICircleComponent.ControlFlow.Continue(
            imageIn,
            exits.map { exitPositionFromDirection(pos, it) },
        )
    }

    /**
     * 波此刻贴着本件的哪几片：来源片里够到共享面的那些，配上本件里与之相交的片。
     *
     * 来源不认识（促动石、导向石、别的环件）时返回本件两片（取宽）；一片都对不上时也返回
     * 两片，因为那种来向已经被 [canEnterFromDirection] 拒在门外，这里只是不该崩。
     */
    fun carryingSlabs(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: Level,
    ): List<Slab> {
        val mine = slabsOf(bs)
        val src = slabsAt(level.getBlockState(pos.relative(enterDir.opposite))) ?: return mine
        val axis = enterDir.axis
        val hit = src
            .filter { s -> s.t != axis }
            .flatMap { s -> mine.filter { m -> meets(s, m, axis) } }
        return hit.distinct().ifEmpty { mine }
    }

    companion object {
        /** 水平朝向：竖墙贴住的那一面。 */
        @JvmField
        val FACING: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING

        /** 与 [BlockStateProperties.HALF] 同一个属性实例（`half=top` / `half=bottom`）。 */
        @JvmField
        val HALF: EnumProperty<Half> = BlockStateProperties.HALF

        /** 底板：贴地 `y 0..1` / 贴天花板 `y 15..16`（模型元素绕 X 翻 180°）。 */
        private val PLATES: Map<Half, VoxelShape> = mapOf(
            Half.BOTTOM to Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0),
            Half.TOP to Block.box(0.0, 15.0, 0.0, 16.0, 16.0, 16.0),
        )

        /**
         * 竖墙：模型里是 `x 0..1, z 0..16`（与底板同厚，1px）。
         *
         * - 贴地：`y 1..16`（坐在底板上）
         * - 贴天花板：`y 0..15`（从翻转后的底板垂下，正好接上）
         */
        private val WALLS: Map<Half, Map<Direction, VoxelShape>> = mapOf(
            Half.BOTTOM to mapOf(
                Direction.WEST to Block.box(0.0, 1.0, 0.0, 1.0, 16.0, 16.0),
                Direction.EAST to Block.box(15.0, 1.0, 0.0, 16.0, 16.0, 16.0),
                Direction.NORTH to Block.box(0.0, 1.0, 0.0, 16.0, 16.0, 1.0),
                Direction.SOUTH to Block.box(0.0, 1.0, 15.0, 16.0, 16.0, 16.0),
            ),
            Half.TOP to mapOf(
                Direction.WEST to Block.box(0.0, 0.0, 0.0, 1.0, 15.0, 16.0),
                Direction.EAST to Block.box(15.0, 0.0, 0.0, 16.0, 15.0, 16.0),
                Direction.NORTH to Block.box(0.0, 0.0, 0.0, 16.0, 15.0, 1.0),
                Direction.SOUTH to Block.box(0.0, 0.0, 15.0, 16.0, 15.0, 16.0),
            ),
        )

        /** `half` → `facing` → 底板 ∪ 竖墙（8 个状态全部预计算）。 */
        private val SHAPES: Map<Half, Map<Direction, VoxelShape>> =
            PLATES.mapValues { (half, plate) ->
                WALLS.getValue(half).mapValues { (_, wall) -> Shapes.or(plate, wall) }
            }
    }
}
