package cn.xm1221.abadoned_greatwork.forge

import dev.architectury.platform.forge.EventBuses
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.forge.datagen.ForgeAbadoned_greatworkDatagen
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraftforge.client.event.EntityRenderersEvent
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
        // 必须在任何事件触发前注册，RegisterRenderers 可能早于 FMLClientSetupEvent
        MOD_BUS.addListener { e: EntityRenderersEvent.RegisterRenderers ->
            for (type in listOf(
                Abadoned_greatworkEntityTypes.EYE_OF_LOCATING,
                Abadoned_greatworkEntityTypes.EYE_OF_TRACKING,
                Abadoned_greatworkEntityTypes.EYE_OF_WAYPOINT,
            )) {
                e.registerEntityRenderer(type.value) { ctx -> ThrownItemRenderer(ctx) }
            }
        }
        Abadoned_greatwork.init()
    }
}
