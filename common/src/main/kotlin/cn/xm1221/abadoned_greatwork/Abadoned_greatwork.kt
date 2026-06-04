package cn.xm1221.abadoned_greatwork

import net.minecraft.resources.ResourceLocation
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import cn.xm1221.abadoned_greatwork.networking.Abadoned_greatworkNetworking
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkActions

object Abadoned_greatwork {
    const val MODID = "abadoned_greatwork"

    @JvmField
    val LOGGER: Logger = LogManager.getLogger(MODID)

    @JvmStatic
    fun id(path: String) = ResourceLocation(MODID, path)

    fun init() {
        Abadoned_greatworkServerConfig.init()
        initRegistries(
            Abadoned_greatworkActions,
        )
        Abadoned_greatworkNetworking.init()
    }

    fun initServer() {
        Abadoned_greatworkServerConfig.initServer()
    }
}
