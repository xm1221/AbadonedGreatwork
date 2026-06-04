package cn.xm1221.abadoned_greatwork.forge

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent

object ForgeAbadoned_greatworkServer {
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLDedicatedServerSetupEvent) {
        Abadoned_greatwork.initServer()
    }
}
