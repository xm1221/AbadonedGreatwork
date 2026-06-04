package cn.xm1221.abadoned_greatwork.forge

import cn.xm1221.abadoned_greatwork.Abadoned_greatworkClient
import net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import thedarkcolour.kotlinforforge.forge.LOADING_CONTEXT

object ForgeAbadoned_greatworkClient {
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLClientSetupEvent) {
        Abadoned_greatworkClient.init()
        LOADING_CONTEXT.registerExtensionPoint(ConfigScreenFactory::class.java) {
            ConfigScreenFactory { _, parent -> Abadoned_greatworkClient.getConfigScreen(parent) }
        }
    }
}
