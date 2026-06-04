package cn.xm1221.abadoned_greatwork.forge

import dev.architectury.platform.forge.EventBuses
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.forge.datagen.ForgeAbadoned_greatworkDatagen
import net.minecraftforge.fml.common.Mod
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(Abadoned_greatwork.MODID)
class ForgeAbadoned_greatwork {
    init {
        MOD_BUS.apply {
            EventBuses.registerModEventBus(Abadoned_greatwork.MODID, this)
            addListener(ForgeAbadoned_greatworkClient::init)
            addListener(ForgeAbadoned_greatworkDatagen::init)
            addListener(ForgeAbadoned_greatworkServer::init)
        }
        Abadoned_greatwork.init()
    }
}
