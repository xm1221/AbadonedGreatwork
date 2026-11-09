package cn.xm1221.abadoned_greatwork.casting.circles;

import at.petrak.hexcasting.api.casting.circles.CircleExecutionState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Collection;
import java.util.List;

/**
 * 「能被真并行分流 / 合并寄生的促动石」鸭子接口。
 *
 * <p>由 {@code cn.xm1221.abadoned_greatwork.mixin.ImpetusBranchMixin} 织进 HexCasting 的
 * {@link at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus}：
 * 促动石本来只存一条 {@code executionState}，这里给它加
 * <ul>
 *   <li>一张「分支波」列表，每 tick 跟着主波一起走（真并行分流）；</li>
 *   <li>一张「合并点到达登记表」，供多路汇一路攒够数量（{@link MergeBuffer}）。</li>
 * </ul>
 *
 * <p>拿不到这个接口时（比如别家的促动石），分流器会退化成老老实实交出全部出口，
 * 让引擎自己报 {@code many_exits}，不会静默丢路；合并则退化成普通直通。
 */
public interface ImpetusBranchHost {

    /** 活着（还没走完）的分支波。**可能为空表，不要缓存引用**。 */
    List<CircleExecutionState> abwBranches();

    /** 挂一条新分支上去，下一 tick 开始跟着走。 */
    void abwAddBranch(CircleExecutionState branch);

    /**
     * 取（必要时新建）某个合并点的登记表。
     *
     * @param pos    合并点坐标
     * @param outDir 那一格唯一的可行出口，栅栏放行时用
     */
    MergeBuffer abwMergeBuffer(BlockPos pos, Direction outDir);

    /** 合并完成（或放弃）后清掉这个点的登记表。 */
    void abwClearMergeBuffer(BlockPos pos);

    /** 当前所有还没凑齐的合并点。栅栏判定要遍历它。 */
    Collection<MergeBuffer> abwMergeBuffers();
}
