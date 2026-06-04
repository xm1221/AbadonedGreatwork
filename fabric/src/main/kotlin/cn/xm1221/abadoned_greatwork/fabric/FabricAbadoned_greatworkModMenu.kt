package cn.xm1221.abadoned_greatwork.fabric

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import cn.xm1221.abadoned_greatwork.Abadoned_greatworkClient

object FabricAbadoned_greatworkModMenu : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory(Abadoned_greatworkClient::getConfigScreen)
}
