package cn.xm1221.abadoned_greatwork.registry;

import at.petrak.hexcasting.api.addldata.ADIotaHolder;
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork;
import gay.object.ioticblocks.api.IoticBlocksAPI;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 把「玩家能用 iota 读写图案往方块里写东西」这件事交给 IoticBlocks。
 *
 * <h2>为什么不自己实现</h2>
 * 上游 Hex 的「往方块里写 iota」是一整套约定：{@link ADIotaHolder} 接口 + 第三方模组注册入口。
 * 自己造一套会得到两个写入口、两套 NBT，还没有 iota 图案那种逐格尼斯的写入手感。
 * 这里只是登记「哪个方块 id 由谁提供 holder」。
 *
 * <h2>为什么这段是 Java</h2>
 * IoticBlocks 的包名是 {@code gay.object.ioticblocks.api} —— {@code object} 是 Kotlin 关键字，
 * 写 Kotlin 得全程反引号，不如直接丢给 Java。
 */
public final class Abadoned_greatworkIotaHolders {
    private Abadoned_greatworkIotaHolders() {
    }

    /**
     * 只登记一个方块：可编程分流器。别的环件都是纯状态机，没有数据要存。
     *
     * 每次被问就现取方块实体，不缓存 —— 环件会被拆 / 放，缓存一个 holder 就是缓存一个死引用。
     * 方块不在（或者还没同步出方块实体）时返回 {@code null}，上游会当成「这里不能写」。
     */
    public static void init() {
        IoticBlocksAPI.INSTANCE.registerIotaHolderProvider(
            Abadoned_greatwork.id("multi_directrix_p"),
            (level, pos) -> {
                BlockEntity be = level.getBlockEntity(pos);
                return be instanceof ADIotaHolder holder ? holder : null;
            }
        );
    }
}
