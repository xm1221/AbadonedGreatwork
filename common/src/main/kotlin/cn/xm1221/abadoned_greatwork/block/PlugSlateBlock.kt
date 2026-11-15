package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.common.blocks.circles.BlockSlate
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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
 * - 点方块的侧面：竖墙贴着被点的那一格（`facing` = 被点面的反方向）；点在侧面上半格时板贴天花板
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
        val half = when {
            clicked == Direction.DOWN -> Half.TOP
            clicked == Direction.UP -> Half.BOTTOM
            context.clickLocation.y - context.clickedPos.y > 0.5 -> Half.TOP
            else -> Half.BOTTOM
        }
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
     * 建环洪泛时扩散的候选方向：从四向非背面中筛出至少有一片在物理上与邻格相接的方向。
     *
     * 非石板邻格（如促动石、导引石、空气）对非薄轴方向视为可通；石板邻格必须与至少一片 [meets]，
     * 避免跨空隙误连到天花板或对侧墙面的石板，同时保证为 [acceptControlFlow] 出口的超集。
     */
    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> {
        val mine = slabsOf(bs)
        val exits = EnumSet.noneOf(Direction::class.java)
        for (dir in openDirections(bs)) {
            val there = level.getBlockState(pos.relative(dir))
            if (mine.any { m -> slabMeetsState(m, there, dir) }) {
                exits.add(dir)
            }
        }
        return exits
    }

    /**
     * **一片 = 薄轴 + 贴合面。**
     *
     * 上游石板一行 `enterDir != normal.opposite` 就够，是因为它一片只有一个法线，`enterDir`
     * 和法线的关系就能定「波在片的哪一侧」。本件两片法线垂直，同一个 `enterDir` 可能是撞板、
     * 也可能是沿墙 —— 差的那半个自由度是**片贴在哪一面**，用一个轴集合表达不出来。
     *
     * 具体匹配见 [carryingSlabs]：来路不在两片贴合背面，且至少有一片能接住来波才允许进入。
     */
    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = carryingSlabs(enterDir, pos, bs, level).isNotEmpty()

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

    /** 除去两片贴合背面（底板背面与竖墙背面）后，几何上可通行/检查的四个方向。 */
    private fun openDirections(bs: BlockState): EnumSet<Direction> {
        val dirs = EnumSet.allOf(Direction::class.java)
        for (s in slabsOf(bs)) {
            dirs.remove(s.g)
        }
        return dirs
    }

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

    /** 本件单片 [slab] 朝 [dir] 看去时，物理边界上是否能与 [targetState] 相通。 */
    private fun slabMeetsState(slab: Slab, targetState: BlockState, dir: Direction): Boolean {
        if (slab.t == dir.axis) return false
        val targetSlabs = slabsAt(targetState) ?: return true
        return targetSlabs.any { s -> s.t != dir.axis && meets(s, slab, dir.axis) }
    }

    /** 本件单片 [slab] 沿 [exit] 出去时，邻格环件是否与该片相接且可进入。 */
    private fun slabAcceptsNext(
        slab: Slab,
        exit: Direction,
        pos: BlockPos,
        level: ServerLevel,
    ): Boolean {
        val therePos = pos.relative(exit)
        val there = level.getBlockState(therePos)
        val next = there.block as? ICircleComponent ?: return false
        return slabMeetsState(slab, there, exit) && next.canEnterFromDirection(exit, therePos, there, level)
    }

    private fun stopManyExits(
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
        env: CircleCastEnv,
    ): ICircleComponent.ControlFlow.Stop {
        val impetus = env.impetus
        if (impetus != null) {
            impetus.postDisplay(
                Component.translatable(
                    "hexcasting.tooltip.circle.many_exits",
                    Component.literal(pos.toShortString()).withStyle(ChatFormatting.RED),
                ),
                ItemStack(Items.COMPASS),
            )
            ICircleComponent.sfx(pos, bs, world, impetus, false)
        }
        return ICircleComponent.ControlFlow.Stop()
    }

    /**
     * 媒质波走到这一格：**按「两块垂直相交的板」判定去向**。
     *
     * 1. 来波必须明确落在其中一块板 [entrySlab] 上；若沿折线同时骑上两片（`carrying.size > 1`）报 `many_exits`；
     * 2. 另一块板 [otherSlab] 在内部始终与 [entrySlab] 相连，因此先检查 [entrySlab] 除来路外
     *    是否还接触任意外部可进环件（含同侧/转角相邻石板）；一旦有，说明 [entrySlab] 存在分叉，
     *    直接报 `many_exits` 并停下；
     * 3. 确认 [entrySlab] 无外部邻接后，波唯一转上 [otherSlab]，交出 [otherSlab] 除来路外所有
     *    物理相接且可进的出口（既可从 `otherSlab.g.opposite` 转出，也可沿 [otherSlab] 折线继续），
     *    由引擎按 0 / 1 / ≥2 处理 `no_exits` / 前进 / `many_exits`。
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
        if (carrying.isEmpty()) {
            return ICircleComponent.ControlFlow.Continue(imageIn, emptyList())
        }

        val entrySlab = carrying.singleOrNull() ?: return stopManyExits(pos, bs, world, env)
        val otherSlab = slabsOf(bs).first { it != entrySlab }
        val open = openDirections(bs)
        val comeFrom = enterDir.opposite

        val entryHasExternalBranch = open.any { dir ->
            dir != comeFrom && slabAcceptsNext(entrySlab, dir, pos, world)
        }
        if (entryHasExternalBranch) {
            return stopManyExits(pos, bs, world, env)
        }

        val exits = open
            .filter { dir -> dir != comeFrom && slabAcceptsNext(otherSlab, dir, pos, world) }
            .map { dir -> exitPositionFromDirection(pos, dir) }

        return ICircleComponent.ControlFlow.Continue(imageIn, exits)
    }

    /**
     * 波此刻贴着本件的哪几片 —— 也就是它在转角里骑在哪块板上。
     *
     * - 若来向面（`enterDir.opposite`）本身是本件某片的贴合背面，物理绝缘，直接返回空集；
     * - 若来源是 [PlugSlateBlock] 或 [BlockSlate]：用 [meets] 匹配所有薄轴不平行于来向且在边界相通的片；
     * - 若来源是其它环件（促动石、导向石等，[slabsAt] 为 `null`）：取本件中薄轴不平行于来向的片。
     *
     * 返回 1 片时说明波明确骑在其中一块板上；返回 2 片说明沿折线同时骑上两片（歧义）。
     */
    fun carryingSlabs(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: Level,
    ): List<Slab> {
        val comeFrom = enterDir.opposite
        val mine = slabsOf(bs)
        if (mine.any { it.g == comeFrom }) return emptyList()
        val axis = enterDir.axis
        val src = slabsAt(level.getBlockState(pos.relative(comeFrom)))
            ?: return mine.filter { m -> m.t != axis }
        return src
            .filter { s -> s.t != axis }
            .flatMap { s -> mine.filter { m -> meets(s, m, axis) } }
            .distinct()
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
