package cn.xm1221.abadoned_greatwork.mixin;

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent;
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus;
import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import cn.xm1221.abadoned_greatwork.casting.circles.ImpetusBranchHost;
import cn.xm1221.abadoned_greatwork.casting.circles.MergeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 HexCasting 的促动石加「多波并行」支持：**真并行分流** + **多路合并**。
 *
 * <h2>为什么要动促动石</h2>
 * 上游的 {@code tickExecution} 只推进一条 {@code executionState}，而且
 * {@code CircleExecutionState.tick} 里「第二个能接的出口 → many_exits → 停机」是写死的。
 * 想真正同时跑多条波，就必须有人在每 tick 调它们各自的 {@code tick(impetus)}，
 * 而全环只有促动石这一个天然的调度者。
 *
 * <h2>五条注入</h2>
 * <ul>
 *   <li>{@code tickExecution} 尾部：推进分支波、跑合并栅栏、收尾、续调度（见下）。</li>
 *   <li>{@code startExecution} 头部：开新一次施法前清掉上一次的残党。</li>
 *   <li>{@code saveModData} / {@code loadModData}：分支列表存盘、读盘。
 *       读盘时世界可能还没就绪，所以先存成 NBT，等第一次 tick 再反序列化
 *       （上游自己也是这么处理 {@code executionState} 的）。</li>
 * </ul>
 *
 * <h2>tickExecution 尾部做的事（顺序不能换）</h2>
 * <ol>
 *   <li><b>推进分支</b>：走快照，分支路过别处的分流器时还会往表里加新的。</li>
 *   <li><b>合并栅栏</b>：一条活波都没有了、可还有波卡在合并点等凑数 → 凑不齐也放出去。</li>
 *   <li><b>收尾</b>：彻底没波了就补一次 {@code endExecution} 熄灯，然后清干净。</li>
 *   <li><b>续调度</b>：主波已经没了但分支还活着时，上游根本走不到
 *       {@code scheduleTick}（它在 {@code executionState == null} 处直接 return），
 *       这里替它按 {@code getTickSpeed()} 的同一公式续上去。</li>
 * </ol>
 *
 * <h2>为什么要「栅栏」，而不是数够就触发</h2>
 * 引擎里的波没有等待态，先到的波只能 {@code Stop()} 掉把镜像留在 {@link MergeBuffer} 里。
 * 万一某个支路半路死了、永远不会再来，光靠「数够」就永远等不到。改成「**全环一条活波都没有**」
 * 就一定会触发 —— 每条波都必然在有限时间内结束，这个状态必然出现，不会死锁。
 *
 * <h2>熄灯</h2>
 * 分支和主波**共用同一个 {@code reachedPositions} 列表**（见 ForkedExecutionState），
 * 所以主波结束时上游那次 {@code endExecution} 会顺手把分支点亮的格子熄掉。
 * 但栅栏放行出来的新波是主波死后才动的，它点亮的格子没人管，所以第 3 步要自己再补一次
 * （{@code endExecution} 只是遍历 {@code reachedPositions} 调 {@code endEnergized}，幂等）。
 *
 * <h2>一个必须知道的上游细节</h2>
 * {@code BlockAbstractImpetus.tick} 长得是
 * {@code if (blockEntity instanceof ... && pState.getValue(ENERGIZED)) tile.tickExecution();}
 * —— **促动石不亮就根本不推进**。而主波死掉时上游的 {@code endExecution()} 会把
 * {@code reachedPositions}（含促动石自己）全部熄灭。所以续调度之前必须先把促动石重新点亮，
 * 否则排进去的 tick 什么都不做，分支永远停在那儿。
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li>合并点里攒的是已经 {@code Stop()} 掉的波，没有对应的活波可以存盘，所以**世界在施法
 *       中途保存时这部分会丢**（分支本身是存盘的，不会坏档）。</li>
 *   <li>施法中把促动石拆了：上游 {@code onRemove} 里的 {@code endExecution()} 会因为
 *       {@code executionState == null} 直接返回，分支不会被清，那些格子会一直亮着。</li>
 * </ul>
 *
 * <p>方法名都是 HexCasting 自己的、不是 MC 重写，所以一律 {@code remap = false}
 * （hexcasting 是模组类，不在 MC 的混淆映射里，不加会找不到 target）。
 */
@Mixin(BlockEntityAbstractImpetus.class)
public abstract class ImpetusBranchMixin implements ImpetusBranchHost {

    @Unique
    private static final String ABW_TAG_BRANCHES = "abw_branches";

    @Shadow(remap = false)
    @Nullable
    protected CircleExecutionState executionState;

    /** 分支波。**不要内联初始化**，Mixin 对实例字段初始化器的支持不稳，一律惰性建。 */
    @Unique
    @Nullable
    private List<CircleExecutionState> abw$branches;

    /** 合并点登记表。同上，惰性建。 */
    @Unique
    @Nullable
    private Map<BlockPos, MergeBuffer> abw$mergeBuffers;

    /** 最近一次见到的波，收尾熄灯时要借它的共享 {@code reachedPositions}。每次施法开头清空。 */
    @Unique
    @Nullable
    private CircleExecutionState abw$last;

    /** 已读盘但世界还没就绪，暂存原始 NBT。 */
    @Unique
    @Nullable
    private ListTag abw$lazyBranches;

    // ==================== 接口实现 ====================

    @Unique
    @Override
    public List<CircleExecutionState> abwBranches() {
        if (this.abw$branches == null) {
            this.abw$branches = new ArrayList<>();
        }
        return this.abw$branches;
    }

    @Unique
    @Override
    public void abwAddBranch(CircleExecutionState branch) {
        this.abwBranches().add(branch);
    }

    @Unique
    @Override
    public MergeBuffer abwMergeBuffer(BlockPos pos, Direction outDir) {
        if (this.abw$mergeBuffers == null) {
            this.abw$mergeBuffers = new HashMap<>();
        }
        return this.abw$mergeBuffers.computeIfAbsent(pos, p -> new MergeBuffer(p, outDir));
    }

    @Unique
    @Override
    public void abwClearMergeBuffer(BlockPos pos) {
        if (this.abw$mergeBuffers != null) {
            this.abw$mergeBuffers.remove(pos);
        }
    }

    @Unique
    @Override
    public Collection<MergeBuffer> abwMergeBuffers() {
        if (this.abw$mergeBuffers == null) {
            this.abw$mergeBuffers = new HashMap<>();
        }
        return this.abw$mergeBuffers.values();
    }

    // ==================== 注入 ====================

    @Unique
    private static int abw$tickSpeed(CircleExecutionState any) {
        // 照抄 CircleExecutionState.getTickSpeed()（它是 protected，这里只能自己算一遍）
        return Math.max(2, 10 - (any.reachedPositions.size() - 1) / 3);
    }

    @Inject(method = "tickExecution", at = @At("RETURN"), remap = false)
    private void abw$tickBranches(CallbackInfo ci) {
        var hasLazy = this.abw$lazyBranches != null;
        var branches = this.abw$branches;
        var buffers = this.abw$mergeBuffers;
        if (branches == null && !hasLazy && (buffers == null || buffers.isEmpty())) {
            return;
        }

        var self = (BlockEntityAbstractImpetus) (Object) this;

        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // 惰性反序列化（上游也是等世界就绪才 load 自己的 executionState）
        if (this.abw$lazyBranches != null) {
            var lazy = this.abw$lazyBranches;
            this.abw$lazyBranches = null;
            for (int i = 0; i < lazy.size(); i++) {
                this.abwAddBranch(CircleExecutionState.load(lazy.getCompound(i), level));
            }
            branches = this.abw$branches;
        }

        // ① 推进分支。走快照：分支自己路过另一个分流器时会往这张表里加新分支，
        //    直接迭代会 CME。新加的分支这一 tick 不跑，下一 tick 开始跑 —— 和主波分流的时序一致。
        if (branches != null && !branches.isEmpty()) {
            for (var branch : new ArrayList<>(branches)) {
                this.abw$last = branch;
                if (!branch.tick(self)) {
                    branches.remove(branch);
                }
            }
        }

        // ② 栅栏：一条活波都没有了，但还有波卡在合并点等凑数 → 凑不齐也放出去
        var anyLive = this.executionState != null || (branches != null && !branches.isEmpty());
        if (!anyLive && this.abw$mergeBuffers != null && !this.abw$mergeBuffers.isEmpty()) {
            for (var buf : new ArrayList<>(this.abw$mergeBuffers.values())) {
                var spawned = buf.flushToBranch();
                if (spawned != null) {
                    this.abw$last = spawned;
                    this.abwAddBranch(spawned);
                }
            }
            this.abw$mergeBuffers.clear();
            anyLive = this.abw$branches != null && !this.abw$branches.isEmpty();
        }

        // ③ 彻底结束：补一次熄灯（幂等），清干净
        if (!anyLive) {
            if (this.abw$last != null) {
                this.abw$last.endExecution(self);
                this.abw$last = null;
            }
            if (branches != null) {
                branches.clear();
            }
            if (this.abw$mergeBuffers != null) {
                this.abw$mergeBuffers.clear();
            }
            return;
        }

        // ④ 主波没了、分支还活着：上游在 executionState == null 处直接 return，
        //    根本不会 scheduleTick，这里替它续上，否则分支会卡死在半路。
        if (this.executionState == null) {
            var any = (branches != null && !branches.isEmpty()) ? branches.get(0) : null;
            if (any != null) {
                // 关键一环：BlockAbstractImpetus.tick 只在 ENERGIZED 时才调 tickExecution。
                // 主波死掉时上游的 endExecution() 已经把所有 reachedPositions（含促动石自己）
                // 熄灭了，不重新点亮的话下面这句 scheduleTick 排进去的 tick 会因为 ENERGIZED=false
                // 直接什么都不做，分支就永远停在那儿。施法没结束，促动石本就该亮着。
                abw$lightImpetus(self, level);
                level.scheduleTick(self.getBlockPos(), self.getBlockState().getBlock(), abw$tickSpeed(any));
            }
        }
    }

    @Unique
    private static void abw$lightImpetus(BlockEntityAbstractImpetus self, ServerLevel level) {
        var bs = self.getBlockState();
        if (bs.getBlock() instanceof BlockCircleComponent cc
            && !cc.isEnergized(self.getBlockPos(), bs, level)) {
            // 走上游自己的点亮路径（setValue + setBlockAndUpdate），别手搓
            cc.startEnergized(self.getBlockPos(), bs, level);
        }
    }

    @Inject(method = "startExecution", at = @At("HEAD"), remap = false)
    private void abw$clearStaleBranches(@Nullable ServerPlayer player, CallbackInfo ci) {
        // 正在跑的时候别动：startExecution 自己也会直接 return
        if (this.executionState != null) {
            return;
        }
        if (this.abw$branches != null) {
            this.abw$branches.clear();
        }
        if (this.abw$mergeBuffers != null) {
            this.abw$mergeBuffers.clear();
        }
        this.abw$last = null;
        this.abw$lazyBranches = null;
    }

    @Inject(method = "saveModData", at = @At("TAIL"), remap = false)
    private void abw$saveBranches(CompoundTag tag, CallbackInfo ci) {
        ListTag list;
        if (this.abw$lazyBranches != null) {
            // 还没反序列化过，原样写回，别丢
            list = this.abw$lazyBranches;
        } else {
            list = new ListTag();
            if (this.abw$branches != null) {
                for (var branch : this.abw$branches) {
                    list.add(branch.save());
                }
            }
        }

        if (!list.isEmpty()) {
            tag.put(ABW_TAG_BRANCHES, list);
        }
    }

    @Inject(method = "loadModData", at = @At("TAIL"), remap = false)
    private void abw$loadBranches(CompoundTag tag, CallbackInfo ci) {
        this.abw$lazyBranches = tag.contains(ABW_TAG_BRANCHES, Tag.TAG_LIST)
            ? tag.getList(ABW_TAG_BRANCHES, Tag.TAG_COMPOUND)
            : null;
        if (this.abw$branches != null) {
            this.abw$branches.clear();
        }
        // 合并点里攒的是已经 Stop() 掉的波，没有对应的活波可以存盘，直接丢
        if (this.abw$mergeBuffers != null) {
            this.abw$mergeBuffers.clear();
        }
        this.abw$last = null;
    }
}
