package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import cn.xm1221.abadoned_greatwork.casting.circles.CircleImageMerge
import cn.xm1221.abadoned_greatwork.casting.circles.ForkedExecutionState
import cn.xm1221.abadoned_greatwork.casting.circles.ImpetusBranchHost
import cn.xm1221.abadoned_greatwork.casting.circles.forkImage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumSet

/**
 * 单个面的端口类型。序列化名直接进 blockstate 的 `when` 条件，所以别改这三个字符串，
 * 改了要同步 `blockstates/multi_directrix.json`。
 *
 * | 值 | 贴图 | 含义 |
 * |---|---|---|
 * | [NONE] | `_0` / `_0_energized` | 未连接，既不接波也不放波（新放置的默认值） |
 * | [IN] | `_2` / `_2_energized` | 输入，波从这一面进来 |
 * | [OUT] | `_1` / `_1_energized` | 输出，波从这一面出去 |
 *
 * 有 [NONE] 这一档以后，**「这个块有几个输入面」是可以精确数出来的**，这正是合并
 * （多路汇一路）判断「到齐了没有」所需要的信息 —— 见 `acceptControlFlow` 的注释。
 */
enum class PortMode(private val id: String) : StringRepresentable {
    NONE("none"),
    IN("in"),
    OUT("out");

    override fun getSerializedName(): String = id
}

/**
 * 多向导引石 — 法术环的分流 / 合并节点（实心本体）。
 *
 * ## 状态
 * 六个面各自一个 [PortMode]（属性名 `north` / `east` / `south` / `west` / `up` / `down`），
 * 加上 `BlockCircleComponent` 提供的 `energized`，共 3⁶ × 2 = 1458 个状态。
 *
 * - 放置时六面都是 [PortMode.NONE]（贴图 `_0`），整块是「全封死的实心方块」，什么都不接。
 * - **潜行右键点某一面**，那一面按 `未连接 → 输入 → 输出 → 未连接` 循环
 *   （贴图在 `_0` / `_2` / `_1` 之间切），音高分别 0.8 / 1.4 / 1.0。
 * - 媒质波经过时 `energized = true`，六面贴图一起换成各自的 `_energized`。
 *
 * ## 渲染
 * 1458 个状态不写 1458 个模型，用 `multipart` + **一个朝北的面片**（配 6 张贴图）：
 *
 * ```
 * north → 原样          east  → y=90        south → y=180
 * west  → y=270         up    → x=270       down  → x=90
 * ```
 *
 * 这组映射是按 MC 自己的 `BlockModelRotation` 推出来的 —— 它的变换是
 * `Ry(-y)·Rx(-x)`，把法线 `(0,0,-1)` 代进去：`y=90` 得 `+X`、`x=90` 得 `-Y`、
 * `x=270` 得 `+Y`，与 vanilla `glow_lichen` 的写法一致。
 * 每一条都带 `uvlock: true`：`uvlock` 会把 UV 反向补偿回该面原本的朝向
 * （`FaceBakery.recomputeUVs`），四张图都没有任何旋转对称性，不加的话侧面图案会侧躺。
 *
 * 方块本身仍是**整格实心**（没有 `noOcclusion()`），贴图全不透明，走默认 solid 渲染层，
 * 不需要 cutout。
 *
 * ## 法术环
 * 输入面接波、输出面放波。见 [acceptControlFlow]：`out` 一个 = 合并，
 * `out` 多个 = 真并行分流（已接通）。
 */
class MultiDirectrixBlock(properties: Properties) : BlockCircleComponent(properties) {

    init {
        var state = stateDefinition.any().setValue(ENERGIZED, false)
        for (prop in PORTS.values) {
            state = state.setValue(prop, PortMode.NONE)
        }
        registerDefaultState(state)
    }

    // ==================== 状态 ====================

    /**
     * 父类的 `createBlockStateDefinition` 只注册 `energized`，**必须先 `super`**，
     * 漏了不会立刻报错，等媒质波第一次点亮这一格才炸（`setValue` 未注册属性）。
     */
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        for (prop in PORTS.values) {
            builder.add(prop)
        }
    }

    // ==================== 手动切换端口 ====================

    /**
     * 潜行右键切换**被点中的那一面**：`未连接 → 输入 → 输出 → 未连接`。
     *
     * `hit.direction` 就是这一面的外法线，所以不用去算玩家视线。非潜行时返回
     * [InteractionResult.PASS]，把右键让给正常放方块。
     *
     * 三段轮换的音高按「越活跃越高」给（0.8 / 1.4 / 1.0），闭着眼睛也能听出点到第几档。
     */
    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!player.isShiftKeyDown) return InteractionResult.PASS
        val prop = PORTS[hit.direction] ?: return InteractionResult.PASS
        // 显式标成非空类型：getValue 是 Java 平台类型，不标的话 when 会被要求处理 null 分支
        val current: PortMode = state.getValue(prop)
        val next = when (current) {
            PortMode.NONE -> PortMode.IN
            PortMode.IN -> PortMode.OUT
            PortMode.OUT -> PortMode.NONE
        }

        if (!level.isClientSide) {
            level.setBlockAndUpdate(pos, state.setValue(prop, next))
            level.playSound(
                null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f,
                when (next) {
                    PortMode.IN -> 1.4f
                    PortMode.OUT -> 1.0f
                    PortMode.NONE -> 0.8f
                },
            )
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // ==================== 结构方块旋转 / 镜像 ====================

    /**
     * 六个属性都是「朝着某个方向」，所以整体转动时属性要跟着换位，否则拿结构方块一转，
     * 端口就指错方向了。一律**从原始 `state` 读、往新 `state` 写**，避免链式 setValue 的顺序坑。
     */
    override fun rotate(state: BlockState, rotation: Rotation): BlockState {
        var out = state
        for (dir in PORTS.keys) {
            out = out.setValue(PORTS.getValue(rotation.rotate(dir)), state.getValue(PORTS.getValue(dir)))
        }
        return out
    }

    /** `Mirror.LEFT_RIGHT` 翻 Z 轴（N/S 互换）、`FRONT_BACK` 翻 X 轴（E/W 互换），交给原版算。 */
    override fun mirror(state: BlockState, mirror: Mirror): BlockState {
        var out = state
        for (dir in PORTS.keys) {
            out = out.setValue(PORTS.getValue(mirror.mirror(dir)), state.getValue(PORTS.getValue(dir)))
        }
        return out
    }

    // ==================== 法术环 ====================

    /**
     * 只把波从「贴着本块的那一侧」的 `in` 面放进来。
     *
     * `enterDir` 是**行进方向**，所以波穿过的是 `enterDir.opposite` 那一面
     * （和 `PlugSlateBlock` 里 `pos.relative(enterDir.opposite)` 取来源格是同一个约定）。
     */
    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = bs.getValue(PORTS.getValue(enterDir.opposite)) == PortMode.IN

    /**
     * 建环洪泛时允许扩散的方向 = 所有 `out` 面。
     *
     * 一个 `out` 都没设时返回空集，环建到这里就走不下去了 —— 这是有意的：全新放下的
     * 分流器六面全 `in`，本来就不该能过波。
     */
    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> {
        val exits = EnumSet.noneOf(Direction::class.java)
        for (dir in DIRECTIONS) {
            if (bs.getValue(PORTS.getValue(dir)) == PortMode.OUT) exits.add(dir)
        }
        return exits
    }

    /**
     * 波走到这一格：**真并行分流** / **多路合并**。
     *
     * 先把 `out` 面过滤成「下一格真的肯接」的 [viable] —— 不然会把波塞进一个当拍就拒绝的
     * 方向，那条分支等于白死。然后看 [viable] 的数量分三种情况。
     *
     * ## 一、`viable` ≥ 2 → 分流
     * `CircleExecutionState.tick` 的逻辑是「逐个问候选出口的下一格肯不肯接，第二个也能接就报
     * `many_exits` 并停机」（原文见交接文档 §5），引擎只允许一个出口。所以：
     * **第一个**交回引擎推（主波），**其余每个各复制一份镜像**做成 [ForkedExecutionState]
     * 挂到促动石的分支表上（见 [ImpetusBranchHost]）。引擎只看到一条出口，
     * `many_exits` 永远不会触发。
     *
     * 镜像的复制见 [forkImage]：栈共享（`CastingImage` 不可变），只有会被就地改写的
     * `userData` 深拷贝，算子计数归零。
     *
     * ## 二、`viable` == 1 且**输入面 ≥ 2** → 合并
     * 输入面的个数就是玩家声明的**期望到达数**（这正是三档端口里「未连接」那一档存在的理由）。
     * 先到的波 `Stop()` 自己、把镜像留在 [MergeBuffer] 里（引擎的波**没有等待态**，
     * 只能这样停住）；凑到最后一条时由**它**带着 [CircleImageMerge] 的结果继续走，
     * 所以不会多造一条波。
     *
     * 万一支路半路死了、永远凑不齐，靠促动石的**栅栏**兜底：全环一条活波都没有了就凑合放行。
     * 见 `ImpetusBranchMixin`。
     *
     * ## 三、其余（`viable` == 1 且单个输入面 / `viable` == 0）→ 普通直通 / 无路可走
     *
     * 促动石不是 HexCasting 的（拿不到 [ImpetusBranchHost]）时，分流退回「老实交出全部出口」
     * 让引擎自己报 `many_exits`，合并退回普通直通 —— 宁可报错也不静默丢路。
     */
    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel,
    ): ICircleComponent.ControlFlow {
        val viable = possibleExitDirections(pos, bs, world).filter { dir ->
            val next = pos.relative(dir)
            val there = world.getBlockState(next)
            (there.block as? ICircleComponent)
                ?.canEnterFromDirection(dir, next, there, world) == true
        }
        val host = env.impetus as? ImpetusBranchHost

        // ===== 一、分流 =====
        if (viable.size > 1) {
            if (host == null) {
                return ICircleComponent.ControlFlow.Continue(
                    imageIn, viable.map { exitPositionFromDirection(pos, it) })
            }
            // imageIn 本身不可变，engine 之后会自己 withOverriddenUsedOps(0)，所以主波不用动
            val parent = env.circleState()
            for (dir in viable.subList(1, viable.size)) {
                host.abwAddBranch(
                    ForkedExecutionState.fork(
                        parent = parent,
                        currentPos = pos.relative(dir),
                        enteredFrom = dir,
                        image = imageIn.forkImage(),
                    )
                )
            }
            return ICircleComponent.ControlFlow.Continue(
                imageIn, viable.subList(0, 1).map { exitPositionFromDirection(pos, it) })
        }

        val outDir = viable.firstOrNull()
            ?: return ICircleComponent.ControlFlow.Continue(imageIn, emptyList())
        val exits = listOf(exitPositionFromDirection(pos, outDir))

        // ===== 二、合并 =====
        val inCount = DIRECTIONS.count { bs.getValue(PORTS.getValue(it)) == PortMode.IN }
        if (host != null && inCount >= 2) {
            val buffer = host.abwMergeBuffer(pos, outDir)
            buffer.arrivals.add(env.circleState())
            if (buffer.arrivals.size < inCount) {
                // 还没到齐：停下等。Stop 不报错、格子保持点亮，镜像由登记表保管
                return ICircleComponent.ControlFlow.Stop()
            }
            val merged = CircleImageMerge.merge(buffer.arrivals.map { it.currentImage })
            host.abwClearMergeBuffer(pos)
            return ICircleComponent.ControlFlow.Continue(merged, exits)
        }

        // ===== 三、普通直通 =====
        return ICircleComponent.ControlFlow.Continue(imageIn, exits)
    }

    /** 整格实心方块，没有「朝上」的概念，粒子方向取默认的 UP。 */
    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        Direction.UP

    /** `vpos = 格心 + 法线 × height`，0.5 落在顶面中心（原版促动石同值）。 */
    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f

    companion object {
        /**
         * 固定顺序（北东南西上下），只影响遍历顺序和 JSON 里的键序，**必须声明在 [PORTS] 之前**：
         * Kotlin 的伴生对象属性按源码顺序初始化，写在后面会拿到 `null`。
         */
        private val DIRECTIONS: Array<Direction> = arrayOf(
            Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST, Direction.UP, Direction.DOWN,
        )

        /** 六个面 → 各自的端口属性。属性名就是 blockstate 里的键名（`north` / `east` / …）。 */
        @JvmField
        val PORTS: Map<Direction, EnumProperty<PortMode>> =
            DIRECTIONS.associateWith { EnumProperty.create(it.serializedName, PortMode::class.java) }
    }
}

