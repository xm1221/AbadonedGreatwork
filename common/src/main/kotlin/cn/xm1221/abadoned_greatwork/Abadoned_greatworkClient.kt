package cn.xm1221.abadoned_greatwork

import cn.xm1221.abadoned_greatwork.block.entity.MultiDirectrixPBlockEntity
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkClientConfig
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlocks
import dev.architectury.registry.client.rendering.ColorHandlerRegistry
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
        registerBlockTints()
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

    /**
     * 可编程分流器的附加层面片染色。
     *
     * `_p_1`（输出）是**纯灰阶**图，乘上 [MultiDirectrixPBlockEntity.tint] 才能出颜色；
     * `_p_2`（关闭）自带暗紫，不受染色影响（它没有 `tintindex`）。
     *
     * 颜色由服务端按**栈顶 iota 的类型**刷进方块实体（借 Hex 自己的类型配色），
     * 这里只负责读。读不到方块实体（比如区块还没同步到）时给白色 = 贴图原样。
     */
    @Environment(EnvType.CLIENT)
    private fun registerBlockTints() {
        ColorHandlerRegistry.registerBlockColors(
            { _, world, pos, _ ->
                val be = if (world != null && pos != null) {
                    world.getBlockEntity(pos) as? MultiDirectrixPBlockEntity
                } else {
                    null
                }
                be?.tint ?: MultiDirectrixPBlockEntity.DEFAULT_TINT
            },
            Abadoned_greatworkBlocks.MULTI_DIRECTRIX_P.value,
        )
    }
}
