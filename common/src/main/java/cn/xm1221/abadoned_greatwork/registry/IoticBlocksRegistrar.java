package cn.xm1221.abadoned_greatwork.registry;

import at.petrak.hexcasting.api.addldata.ADIotaHolder;
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork;
import gay.object.ioticblocks.api.IoticBlocksAPI;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Loaded only after {@link Abadoned_greatworkIotaHolders} has confirmed IoticBlocks is present.
 * Keeping the API reference in this class means a missing mod does not prevent the mod class
 * itself from loading.
 */
final class IoticBlocksRegistrar {
    private IoticBlocksRegistrar() {
    }

    static void registerAll() {
        registerHolder("multi_directrix_p");
        registerHolder("wave_transmitter");
        registerHolder("wave_receiver");
        registerHolder("ambit_switch");
        registerHolder("ambit_anchor");
    }

    private static void registerHolder(String path) {
        IoticBlocksAPI.INSTANCE.registerIotaHolderProvider(
            Abadoned_greatwork.id(path),
            (level, pos) -> {
                BlockEntity be = level.getBlockEntity(pos);
                return be instanceof ADIotaHolder holder ? holder : null;
            }
        );
    }
}
