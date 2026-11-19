package cn.xm1221.abadoned_greatwork.forge

import cn.xm1221.abadoned_greatwork.Abadoned_greatworkClient
import cn.xm1221.abadoned_greatwork.client.ponder.PonderKeybinds
import cn.xm1221.abadoned_greatwork.client.renderer.AmbitBoundaryRenderer
import net.minecraft.client.Minecraft
import net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import thedarkcolour.kotlinforforge.forge.LOADING_CONTEXT

object ForgeAbadoned_greatworkClient {
    fun registerModBusEvents(modBus: IEventBus) {
        modBus.addListener { e: RegisterKeyMappingsEvent ->
            for (bind in PonderKeybinds.allBinds()) {
                e.register(bind)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLClientSetupEvent) {
        Abadoned_greatworkClient.init()
        LOADING_CONTEXT.registerExtensionPoint(ConfigScreenFactory::class.java) {
            ConfigScreenFactory { _, parent -> Abadoned_greatworkClient.getConfigScreen(parent) }
        }
        MinecraftForge.EVENT_BUS.addListener { e: RenderLevelStageEvent ->
            if (e.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return@addListener
            val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
            AmbitBoundaryRenderer.render(
                poseStack = e.poseStack,
                bufferSource = bufferSource,
                cameraPos = e.camera.position,
                partialTick = e.partialTick,
            )
        }
    }
}
