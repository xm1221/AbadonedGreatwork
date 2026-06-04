package cn.xm1221.abadoned_greatwork.fabric

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import net.fabricmc.api.DedicatedServerModInitializer

object FabricAbadoned_greatworkServer : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        Abadoned_greatwork.initServer()
    }
}
