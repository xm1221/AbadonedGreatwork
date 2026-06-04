package cn.xm1221.abadoned_greatwork

import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkClientConfig
import me.shedaniel.autoconfig.AutoConfig
import net.minecraft.client.gui.screens.Screen

object Abadoned_greatworkClient {
    fun init() {
        Abadoned_greatworkClientConfig.init()
    }

    fun getConfigScreen(parent: Screen): Screen {
        return AutoConfig.getConfigScreen(Abadoned_greatworkClientConfig.GlobalConfig::class.java, parent).get()
    }
}
