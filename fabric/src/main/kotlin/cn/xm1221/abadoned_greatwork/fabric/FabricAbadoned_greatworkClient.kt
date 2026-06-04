package cn.xm1221.abadoned_greatwork.fabric

import cn.xm1221.abadoned_greatwork.Abadoned_greatworkClient
import net.fabricmc.api.ClientModInitializer

object FabricAbadoned_greatworkClient : ClientModInitializer {
    override fun onInitializeClient() {
        Abadoned_greatworkClient.init()
    }
}
