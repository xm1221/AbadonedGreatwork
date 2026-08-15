package cn.xm1221.abadoned_greatwork.fabric

import cn.xm1221.abadoned_greatwork.Abadoned_greatworkClient
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.renderer.entity.ThrownItemRenderer

object FabricAbadoned_greatworkClient : ClientModInitializer {
    override fun onInitializeClient() {
        Abadoned_greatworkClient.init()
        // 各探察之眼复用原版末影之眼的渲染方式（渲染实体持有的物品模型）
        for (type in listOf(
            Abadoned_greatworkEntityTypes.EYE_OF_LOCATING,
            Abadoned_greatworkEntityTypes.EYE_OF_TRACKING,
            Abadoned_greatworkEntityTypes.EYE_OF_WAYPOINT,
        )) {
            EntityRendererRegistry.register(type.value) { ctx -> ThrownItemRenderer(ctx) }
        }
    }
}
