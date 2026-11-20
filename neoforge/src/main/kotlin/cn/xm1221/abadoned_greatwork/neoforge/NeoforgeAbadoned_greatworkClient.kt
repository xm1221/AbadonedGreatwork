package cn.xm1221.abadoned_greatwork.neoforge

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.Abadoned_greatworkClient
import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.MediaPoolBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.WaveTransmitterBlockEntity
import cn.xm1221.abadoned_greatwork.client.ponder.PonderKeybinds
import cn.xm1221.abadoned_greatwork.client.renderer.AmbitAnchorBlockEntityRenderer
import cn.xm1221.abadoned_greatwork.client.renderer.AmbitBoundaryRenderer
import cn.xm1221.abadoned_greatwork.client.renderer.MediaPoolBlockEntityRenderer
import cn.xm1221.abadoned_greatwork.client.renderer.TruthCrystalBlockEntityRenderer
import cn.xm1221.abadoned_greatwork.client.renderer.WaveTransmitterBlockEntityRenderer
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlockEntities
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkEntityTypes
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge

@Mod(value = Abadoned_greatwork.MODID, dist = [Dist.CLIENT])
class NeoforgeAbadoned_greatworkClient(modBus: IEventBus, container: ModContainer) {
    init {
        modBus.addListener(::onClientSetup)
        modBus.addListener(::onRegisterKeys)
        modBus.addListener(::onRegisterRenderers)
        NeoForge.EVENT_BUS.addListener(::onRenderLevel)
        container.registerExtensionPoint(IConfigScreenFactory::class.java) {
            IConfigScreenFactory { _, parent ->
                Abadoned_greatworkClient.getConfigScreen(parent)
            }
        }
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        event.enqueueWork {
            Abadoned_greatworkClient.init()
        }
    }

    private fun onRegisterKeys(event: RegisterKeyMappingsEvent) {
        for (bind in PonderKeybinds.allBinds()) {
            event.register(bind)
        }
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        for (type in listOf(
            Abadoned_greatworkEntityTypes.EYE_OF_LOCATING,
            Abadoned_greatworkEntityTypes.EYE_OF_TRACKING,
            Abadoned_greatworkEntityTypes.EYE_OF_WAYPOINT,
        )) {
            event.registerEntityRenderer(type.value) { ctx -> ThrownItemRenderer(ctx) }
        }
        @Suppress("UNCHECKED_CAST")
        event.registerBlockEntityRenderer(
            Abadoned_greatworkBlockEntities.TRUTH_CRYSTAL.value as BlockEntityType<TruthCrystalBlockEntity>
        ) { ctx -> TruthCrystalBlockEntityRenderer(ctx) }
        @Suppress("UNCHECKED_CAST")
        event.registerBlockEntityRenderer(
            Abadoned_greatworkBlockEntities.WAVE_TRANSMITTER.value as BlockEntityType<WaveTransmitterBlockEntity>
        ) { ctx -> WaveTransmitterBlockEntityRenderer(ctx) }
        @Suppress("UNCHECKED_CAST")
        event.registerBlockEntityRenderer(
            Abadoned_greatworkBlockEntities.MEDIA_POOL.value as BlockEntityType<MediaPoolBlockEntity>
        ) { ctx -> MediaPoolBlockEntityRenderer(ctx) }
        @Suppress("UNCHECKED_CAST")
        event.registerBlockEntityRenderer(
            Abadoned_greatworkBlockEntities.AMBIT_ANCHOR.value as BlockEntityType<AmbitAnchorBlockEntity>
        ) { ctx -> AmbitAnchorBlockEntityRenderer(ctx) }
    }

    private fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return
        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
        AmbitBoundaryRenderer.render(
            poseStack = event.poseStack,
            bufferSource = bufferSource,
            cameraPos = event.camera.position,
            partialTick = event.partialTick.getGameTimeDeltaPartialTick(false),
        )
    }
}
