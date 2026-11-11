package cn.xm1221.abadoned_greatwork

import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import cn.xm1221.abadoned_greatwork.block.Abadoned_greatworkLootInjector
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import cn.xm1221.abadoned_greatwork.networking.Abadoned_greatworkNetworking
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkActions
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlocks
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkCreativeTabs
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkIotaHolders
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkIotaTypes
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome

object Abadoned_greatwork {
    const val MODID = "abadoned_greatwork"

    @JvmField
    val LOGGER: Logger = LogManager.getLogger(MODID)

    @JvmStatic
    fun id(path: String) = ResourceLocation(MODID, path)

    @JvmStatic
    val EDIFIED_BIOME: ResourceLocation = ResourceLocation(MODID, "edified")

    fun init() {
        Abadoned_greatworkServerConfig.init()
        initRegistries(
            Abadoned_greatworkActions,
            Abadoned_greatworkIotaTypes,
            Abadoned_greatworkBlocks,
            Abadoned_greatworkItems,
            Abadoned_greatworkBlockEntities,
            Abadoned_greatworkEntityTypes,
            Abadoned_greatworkCreativeTabs,
        )
        Abadoned_greatworkNetworking.init()
        Abadoned_greatworkLootInjector.register()
<<<<<<< Updated upstream
        dev.architectury.registry.ReloadListenerRegistry.register(
            net.minecraft.server.packs.PackType.SERVER_DATA,
            net.minecraft.server.packs.resources.ResourceManagerReloadListener { rm ->
                cn.xm1221.abadoned_greatwork.block.Abadoned_greatworkRiddleLoader.reload(rm)
            }
        )
=======
        // 需要「玩家能往方块里写 iota」的方块（可编程分流器）走 IoticBlocks 的入口
        Abadoned_greatworkIotaHolders.init()
>>>>>>> Stashed changes
    }

    fun initServer() {
        Abadoned_greatworkServerConfig.initServer()
    }
}
