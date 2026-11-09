package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.common.blocks.circles.BlockSlate
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import cn.xm1221.abadoned_greatwork.util.isParallel
import net.minecraft.core.BlockPos
import net.minecraft.ChatFormatting
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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

    fun faceDir(pos: BlockPos,bs: BlockState,level: Level):Direction {
        return bs.getValue(FACING)
    }

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f - 15f / 16f

    /**
     * 建环洪泛时扩散的候选方向：**全部 6 个**。
     *
     * 单块石板只有一片，出口只能取「法线以外」；这块是两片（横片 + 竖片），
     * 两片各自禁掉自己暴露面的法线之后取并集，剩下的正好是 6 个方向全通 ——
     * 横片禁 UP（贴地时）、竖片禁朝格内的那一面，但任一方向总有一片能走。
     */
    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> =
        EnumSet.allOf(Direction::class.java)

    /**
     * **来向能贴上本格某一片的暴露面就放行，只有完全不接触才拒。**
     *
     * 波走的是片的**暴露面**，但片本身有厚度：它占 `[offset-1, offset]` 这 1px。
     * 所以判据不是「平面坐标相等」，而是「两片贴得上」——同一法线轴，暴露面相差不超过 1px。
     *
     * 第一步定**来源片**：波在来源格里只能贴着「不被 [enterDir] 穿过」的那一片
     * （`enterDir.axis != 片法线轴`）。本件两片法线垂直（`Y` 与水平 `FACING` 轴），
     * 所以竖直来向只剩竖墙片、沿 `FACING` 轴的来向只剩底板片 —— 除折线方向外唯一；
     * 折线方向两片都成立，刻意保留宽，歧义交给 [acceptControlFlow] 报 `many_exits`。
     *
     * 第二步定**本格片**：来源片与本格某片贴得上就放行。
     *
     * 来源不是本件也不是上游 [BlockSlate] 时（促动石、导向石、别的环件）取宽 ——
     * 那些件的片怎么摆不在本件知识范围内，猜它会误拒合法环。
     */
    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean {
        val srcPos = pos.relative(enterDir.opposite)
        //val srcBs = level.getBlockState(srcPos)
        val srcNormal = normalDirOfOther(srcPos, level, 16)
        val localNormal = normalDir(pos, bs, level)
        val localFace = faceDir(pos, bs, level)
        if(!enterDir.isParallel(localFace) && !enterDir.isParallel(localNormal)) {
            return true
        }
        if(enterDir.isParallel(localFace)) {
            return srcNormal != localNormal.opposite
        }
        return false
    }


    /**
     * 媒质波走到这一格：把镜像原样传下去，只负责转向。
     *
     * 出口 = `possibleExitDirections` 去掉来路。返回多个候选是安全的：引擎会挑出
     * 「下一格愿意接」的那一个，多于一个才报去路过多。
     */
    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val localFace = faceDir(pos, bs, world)
        val localNormal = normalDir(pos, bs, world)
        val srcPos = pos.relative(enterDir.opposite)
        val srcNormal = normalDirOfOther(srcPos, world, 16)
        val exits = possibleExitDirections(pos, bs, world)
        if(srcNormal == localNormal.opposite) {
            for (exit in exits) {
                if(world.getBlockState(pos.relative(exit)).block !is ICircleComponent) exits.remove(exit)
                if (exit == localFace.opposite) continue
                exits.remove(exit)
            }
        }
        if(exits.size>1){
            reportManyExits(env,pos,bs,world)
            return ICircleComponent.ControlFlow.Stop()
        }
        return ICircleComponent.ControlFlow.Continue(
            imageIn,
            exits.map { exitPositionFromDirection(pos, it) },
        )
    }

    /**
     * 原样复刻 `CircleExecutionState.tick` 里 `many_exits` 的那几行：
     * 给促动石挂一条「红字坐标 + 指南针」的显示，再放失败音效；随后由调用方返回 `Stop`。
     * （引擎源码注释：走到 `Stop` 时错误应当已经由 `acceptControlFlow` 自己发过了。）
     */
    private fun reportManyExits(env: CircleCastEnv, pos: BlockPos, bs: BlockState, world: ServerLevel) {
        val impetus = env.impetus ?: return
        impetus.postDisplay(
            Component.translatable(
                "hexcasting.tooltip.circle.many_exits",
                Component.literal(pos.toShortString()).withStyle(ChatFormatting.RED),
            ),
            ItemStack(Items.COMPASS),
        )
        ICircleComponent.sfx(pos, bs, world, impetus, false)
    }

    companion object {
        /**
         * 是否把「来向平行于折线」判为去路过多（此时横片与竖片都认这条波）。
         *
         * `true`  = 转角只用来「转」，不接受沿着墙走直路；
         * `false` = 退化成两片并集的万能接头，任何方向都能过。
         */


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
