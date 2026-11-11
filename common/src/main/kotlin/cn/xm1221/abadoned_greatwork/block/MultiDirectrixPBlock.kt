package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.CircleExecutionState
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.math.HexPattern
import cn.xm1221.abadoned_greatwork.block.entity.MultiDirectrixPBlockEntity
import cn.xm1221.abadoned_greatwork.casting.circles.CircleImageMerge
import cn.xm1221.abadoned_greatwork.casting.circles.ForkedExecutionState
import cn.xm1221.abadoned_greatwork.casting.circles.ImpetusBranchHost
import cn.xm1221.abadoned_greatwork.casting.circles.forkImage
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
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
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.EnumSet

/**
 * 多向导引石·核心 — **可编程**分流 / 合并节点（模型 `block/multi_directrix_p`）。
 *
 * 骨架和 [MultiDirectrixBlock] 一样：六个面各一个 [PortMode]，潜行右键循环
 * `未连接 → 输入 → 输出 → 未连接`，`canEnterFromDirection` / `possibleExitDirections`
 * 的规则也一模一样。**区别只有两处**：
 *
 * 1. 它有一个方块实体，里面存着玩家写进去的**一条图案**（[MultiDirectrixPBlockEntity]，
 *    写入入口由 IoticBlocks 提供）。
 * 2. 媒质波路过时，那一格**每面各跑一遍那条图案**：把出口方向压栈 → 执行 → 结果交给那个出口。
 *
 * ## 贴图口径（和普通版不同，别搞混）
 *
 * | 状态 | 贴图 | 渲染 |
 * |---|---|---|
 * | [PortMode.IN]（默认档位之外的「原本」那一张） | `multi_directrix_p` | 骨架本体，**不贴附加层** |
 * | [PortMode.OUT] | `multi_directrix_p_1`（灰阶） | 本体 + 面片附加层，按栈顶 iota 类型染色 |
 * | [PortMode.NONE] | `multi_directrix_p_2`（暗紫） | 本体 + 面片附加层 |
 *
 * ## 没写图案时
 * 退化成普通 [MultiDirectrixBlock]：合并走 `CircleImageMerge`，分流靠分支波，
 * 出口方向**不会**被压进栈（否则会把普通环的栈弄脏）。
 *
 * ## 写了图案时的完整时序
 *
 * ### 一、合并（输入面 ≥ 2，且促动石支持分支）
 * 每条到达的波 `Stop()` 自己，把镜像交给 [MergeBuffer]；凑够「输入面个数」条之后，
 * 按**到达顺序**把每一支压成一个列表：
 *
 * ```
 * [ ListIota(那一支原本的整个栈), 渡鸦之思 或 null, 来自方向 ]
 * ```
 *
 * 三个都在列表里（不是三样各占一格），所以 N 条波进来 = 出栈 N 个列表，一条不多。
 * 然后**跑一次**图案，得到的东西继续往下走（见第二步）。
 *
 * ### 二、分流（每个出口各一条）
 * 对每个真的会接的出口：
 *
 * ```
 * 记录当前镜像 → 压入出口方向 → 执行图案 → 结果给这个出口 → 调出保存的镜像 → 下一个出口
 * ```
 *
 * `记录 / 调出` 就是 [CastingImage] 不可变的天然好处（见 [forkImage]）。
 * 第一个出口交回引擎推（主波），其余每个出口各做成一条 [ForkedExecutionState] 分支波 ——
 * 引擎只允许一个出口，多出来它会报 `many_exits` 停机。
 *
 * > 多进 + 多出 = 先合并跑一次，再对每个出口各跑一次（已和咩咩确认）。
 *
 * 图案执行失败（莫邪 / 停机）时那条路直接断掉；一条都不剩就 `Stop()`。
 */
class MultiDirectrixPBlock(properties: Properties) : BlockCircleComponent(properties), EntityBlock {

    init {
        var state = stateDefinition.any().setValue(ENERGIZED, false)
        for (prop in PORTS.values) {
            state = state.setValue(prop, PortMode.NONE)
        }
        registerDefaultState(state)
    }

    // ==================== 状态 ====================

    /** 和 [MultiDirectrixBlock] 一样：必须先 `super`（父类只注册 `energized`）。 */
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        for (prop in PORTS.values) {
            builder.add(prop)
        }
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        MultiDirectrixPBlockEntity(Abadoned_greatworkBlockEntities.MULTI_DIRECTRIX_P.value, pos, state)

    /** `EntityBlock` 默认给 `INVISIBLE`，不覆盖整个方块会看不见。 */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /** 8 个 6³ 角块 + 2..14 核心（模型里的两个反向 `core_outline` 只是描边，不算形状）。 */
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPE

    // ==================== 手动切换端口 ====================

    /** 潜行右键切换被点中那一面：`未连接 → 输入 → 输出 → 未连接`。 */
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

    override fun rotate(state: BlockState, rotation: Rotation): BlockState {
        var out = state
        for (dir in PORTS.keys) {
            out = out.setValue(PORTS.getValue(rotation.rotate(dir)), state.getValue(PORTS.getValue(dir)))
        }
        return out
    }

    override fun mirror(state: BlockState, mirror: Mirror): BlockState {
        var out = state
        for (dir in PORTS.keys) {
            out = out.setValue(PORTS.getValue(mirror.mirror(dir)), state.getValue(PORTS.getValue(dir)))
        }
        return out
    }

    // ==================== 法术环 ====================

    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        level: ServerLevel,
    ): Boolean = bs.getValue(PORTS.getValue(enterDir.opposite)) == PortMode.IN

    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, level: Level): EnumSet<Direction> {
        val exits = EnumSet.noneOf(Direction::class.java)
        for (dir in PORTS.keys) {
            if (bs.getValue(PORTS.getValue(dir)) == PortMode.OUT) exits.add(dir)
        }
        return exits
    }

    /**
     * 波走到这一格。整体分三段：**过滤出口 → 合并 → 分流**，详见类注释。
     *
     * `viable` 是「下一格当拍真的肯接」的出口 —— 先过滤掉一半，不然会往死胡同里塞波。
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
        if (viable.isEmpty()) return ICircleComponent.ControlFlow.Continue(imageIn, emptyList())

        val be = world.getBlockEntity(pos) as? MultiDirectrixPBlockEntity
        val program = be?.pattern
        val host = env.impetus as? ImpetusBranchHost

        // 多出口却拿不到促动石（不是 HexCasting 的促动石）：没法 fork，老实把出口全交出去
        // 让引擎自己报 many_exits —— 宁可报错，也不静默丢掉几条路。
        if (host == null && viable.size > 1) {
            return ICircleComponent.ControlFlow.Continue(
                imageIn, viable.map { exitPositionFromDirection(pos, it) })
        }

        // ===== 一、合并：多输入面时先攒够到达数 =====
        val inCount = PORTS.keys.count { bs.getValue(PORTS.getValue(it)) == PortMode.IN }
        val baseImage: CastingImage = if (host != null && inCount >= 2) {
            // 多进多出时，所有到达都登记在「第一个出口」那把钥匙下，凑齐了一起放行
            val buffer = host.abwMergeBuffer(pos, viable.first())
            buffer.arrivals.add(env.circleState())
            if (buffer.arrivals.size < inCount) {
                // 引擎的波没有等待态：Stop 自己、把镜像留在登记表里，格子保持点亮
                return ICircleComponent.ControlFlow.Stop()
            }
            val arrivals = buffer.arrivals.toList()
            host.abwClearMergeBuffer(pos)
            if (program == null) {
                // 没写图案 → 退回普通版的合并语义
                CircleImageMerge.merge(arrivals.map { it.currentImage })
            } else {
                // 每支压成一个 `[栈列表, 渡鸦之思, 来自方向]`，然后跑一次图案
                runProgram(collectArrivals(arrivals, world), env, world, program)
                    ?: return ICircleComponent.ControlFlow.Stop()
            }
        } else {
            imageIn
        }

        // ===== 二、分流：每个出口各跑一遍图案 =====
        val perExit: List<Pair<Direction, CastingImage>> = viable.mapNotNull { dir ->
            if (program == null) {
                // 没写图案：压栈会把普通环的栈弄脏，所以只复制镜像、不压方向
                dir to baseImage.forkImage()
            } else {
                val withDir = baseImage.copy(
                    stack = baseImage.stack + Vec3Iota(Vec3.atLowerCornerOf(dir.normal)),
                )
                runProgram(withDir, env, world, program)?.let { dir to it }
            }
        }
        if (perExit.isEmpty()) return ICircleComponent.ControlFlow.Stop()

        // 染色跟着主出口那条波的栈顶走
        be?.setTint(iotaTintOf(perExit.first().second.stack.lastOrNull()))

        val parent = env.circleState()
        for ((dir, img) in perExit.drop(1)) {
            host?.abwAddBranch(
                ForkedExecutionState.fork(
                    parent = parent,
                    currentPos = pos.relative(dir),
                    enteredFrom = dir,
                    image = img,
                )
            )
        }
        val (primaryDir, primaryImage) = perExit.first()
        return ICircleComponent.ControlFlow.Continue(
            primaryImage, listOf(exitPositionFromDirection(pos, primaryDir)))
    }

    /** 整格方块，没有「朝上」的概念，粒子方向取默认 UP（和普通版一致）。 */
    override fun normalDir(pos: BlockPos, bs: BlockState, level: Level, recursionLeft: Int): Direction =
        Direction.UP

    override fun particleHeight(pos: BlockPos, bs: BlockState, level: Level): Float = 0.5f

    // ==================== 内部：执行 / 收集 ====================

    /**
     * 拿 [image] 当栈、本块的环环境当环境，跑一遍方块里写入的图案。
     *
     * 写法照抄上游 `BlockSlate.acceptControlFlow`（那边也是「一块板岩跑一条图案」）：
     * 成功就取 VM 里的镜像，失败（莫邪 / 停机）返回 `null` 让调用方决定断哪条路。
     *
     * `withOverriddenUsedOps(0)`：一块环件执行完算子计数归零，和引擎推主波时一致
     * （见 `CircleExecutionState.tick` 里的 `cont.update.withOverriddenUsedOps(0)`）。
     */
    private fun runProgram(
        image: CastingImage,
        env: CircleCastEnv,
        world: ServerLevel,
        pattern: HexPattern,
    ): CastingImage? {
        val vm = CastingVM(image, env)
        val result = vm.queueExecuteAndWrapIota(PatternIota(pattern), world)
        if (!result.resolutionType.success) return null
        return vm.image.withOverriddenUsedOps(0)
    }

    /**
     * 合并收集：把每一条到达的波压成一个列表，栈 = 「N 个列表」（按到达顺序，先到的在下）。
     *
     * ```
     * [ ListIota(原本的整个栈), 渡鸦之思 或 null, 来自方向 ]
     * ```
     *
     * 底子取**最后一个到达**的那条的镜像，所以 `userData`（含渡鸦之思）跟着它走；
     * 栈则完全换成那 N 个列表。
     *
     * `来自方向` = 那一支的 `enteredFrom`（行进方向，指向本块的相反侧）。
     */
    private fun collectArrivals(
        arrivals: List<CircleExecutionState>,
        world: ServerLevel,
    ): CastingImage {
        val lists: List<Iota> = arrivals.map { st ->
            val img = st.currentImage
            ListIota(
                listOf(
                    ListIota(img.stack),
                    ravenMindOf(img, world) ?: NullIota(),
                    Vec3Iota(Vec3.atLowerCornerOf(st.enteredFrom.normal)),
                )
            )
        }
        return arrivals.last().currentImage.copy(stack = lists)
    }

    /** 渡鸦之思存在 `userData[hexcasting:ravenmind]` 里（序列化过的 iota），上游 `OpPeekLocal` 也是这么读的。 */
    private fun ravenMindOf(image: CastingImage, world: ServerLevel): Iota? {
        val tag = image.userData.get(HexAPI.RAVENMIND_USERDATA) ?: return null
        return if (tag is CompoundTag) IotaType.deserialize(tag, world) else null
    }

    companion object {
        /** 固定的六面（北东南西上下），共用 [MultiDirectrixBlock] 那套属性实例。 */
        private val PORTS: Map<Direction, net.minecraft.world.level.block.state.properties.EnumProperty<PortMode>> =
            MultiDirectrixBlock.PORTS

        /** 8 个 6³ 角块 + 2..14 核心。 */
        @JvmField
        val SHAPE: VoxelShape = run {
            val starts = listOf(0.0, 10.0)
            val corners = starts.flatMap { x ->
                starts.flatMap { y ->
                    starts.map { z -> Block.box(x, y, z, x + 6.0, y + 6.0, z + 6.0) }
                }
            }.toTypedArray()
            Shapes.or(Block.box(2.0, 2.0, 2.0, 14.0, 14.0, 14.0), *corners)
        }
    }
}

/**
 * 栈顶 iota → 染色。
 *
 * 直接借 Hex 自己的**类型配色表**（`IotaType.color()`，就是施法 GUI 里堆栈列表上那些颜色），
 * 不再另造一套「数=青、矢量=绿」的映射 —— 玩家看到代码框里的颜色和方块上的颜色是同一套。
 *
 * 空栈（或者图案把栈吃空了）给白色，也就是灰阶贴图原样。
 */
internal fun iotaTintOf(iota: Iota?): Int = iota?.type?.color() ?: MultiDirectrixPBlockEntity.DEFAULT_TINT
