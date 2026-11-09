package cn.xm1221.abadoned_greatwork

import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkClientConfig
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlocks
import dev.architectury.registry.client.rendering.RenderTypeRegistry
import me.shedaniel.autoconfig.AutoConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderType

object Abadoned_greatworkClient {
    fun init() {
        Abadoned_greatworkClientConfig.init()
        registerBlockRenderTypes()
    }

    fun getConfigScreen(parent: Screen): Screen {
        return AutoConfig.getConfigScreen(Abadoned_greatworkClientConfig.GlobalConfig::class.java, parent).get()
    }

    /**
     * 注册方块渲染层。
     *
     * `multi_directrix_p` 的模型把框架内侧面刻意映射到贴图的全透明区域，只有走 cutout
     * 这些面才会真的「消失」；留在默认 solid 层会被画成黑块。实心本体 `multi_directrix`
     * 贴图不透明，保持默认 solid 层。
     */
    @Environment(EnvType.CLIENT)
    private fun registerBlockRenderTypes() {
        RenderTypeRegistry.register(
            RenderType.cutout(),
            Abadoned_greatworkBlocks.MULTI_DIRECTRIX_P.value,
        )
    }
}
