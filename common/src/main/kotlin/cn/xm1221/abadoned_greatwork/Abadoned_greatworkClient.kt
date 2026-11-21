package cn.xm1221.abadoned_greatwork

import at.petrak.hexcasting.api.client.ScryingLensOverlayRegistry
import cn.xm1221.MieLib.registry.DrawPatternHook
import cn.xm1221.abadoned_greatwork.block.entity.AmbitAnchorBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.AmbitSwitchBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.MediaPoolBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.MultiDirectrixPBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.WaveRepeaterBlockEntity
import cn.xm1221.abadoned_greatwork.block.entity.WaveTransmitterBlockEntity
import cn.xm1221.abadoned_greatwork.client.gui.skin.GuiSkinLoader
import cn.xm1221.abadoned_greatwork.client.notes.NoteClientCommands
import cn.xm1221.abadoned_greatwork.client.notes.NotePatchouliCompat
import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteLibraryScreen
import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteScrapPreviewScreen
import cn.xm1221.abadoned_greatwork.client.ponder.PonderClientState
import cn.xm1221.abadoned_greatwork.client.ponder.PonderDrawPatternWorker
import cn.xm1221.abadoned_greatwork.client.ponder.PonderPatchouliCompat
import cn.xm1221.abadoned_greatwork.client.ponder.PonderScryingTrigger
import cn.xm1221.abadoned_greatwork.client.renderer.AmbitBoundaryRenderer
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkClientConfig
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkBlocks
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.registry.client.rendering.ColorHandlerRegistry
import dev.architectury.registry.client.rendering.RenderTypeRegistry
import me.shedaniel.autoconfig.AutoConfig
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderType

object Abadoned_greatworkClient {
    fun init() {
        Abadoned_greatworkClientConfig.init()
        registerBlockRenderTypes()
        registerBlockTints()
        registerScryingLensOverlays()

        // 注册 MieLib 问天图案渲染钩子与探知透镜按图案检索触发器
        DrawPatternHook.register(Abadoned_greatwork.id("ponder_pattern_color"), PonderDrawPatternWorker)
        PonderScryingTrigger.init()

        // 贴图驱动 GUI 皮肤、玩家笔记与 Patchouli 注入
        GuiSkinLoader.init()
        NotePatchouliCompat.init()
        NoteClientCommands.init()
        ClientNoteLibrary.openLibraryScreenHook = {
            Minecraft.getInstance().setScreen(NoteLibraryScreen())
        }
        ClientNoteLibrary.openScrapPreviewHook = { entry, offhand ->
            Minecraft.getInstance().setScreen(NoteScrapPreviewScreen(entry, offhand))
        }

        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            AmbitBoundaryRenderer.clear()
            PonderClientState.clear()
            PonderPatchouliCompat.invalidateCache()
            ClientNoteLibrary.clearOnDisconnect()
        }
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
            Abadoned_greatworkBlocks.WAVE_TRANSMITTER.value,
            Abadoned_greatworkBlocks.WAVE_RECEIVER.value,
            Abadoned_greatworkBlocks.WAVE_REPEATER.value,
            Abadoned_greatworkBlocks.MEDIA_POOL.value,
            Abadoned_greatworkBlocks.AMBIT_SWITCH.value,
            Abadoned_greatworkBlocks.AMBIT_ANCHOR.value,
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

    @Environment(EnvType.CLIENT)
    private fun registerScryingLensOverlays() {
        ScryingLensOverlayRegistry.addDisplayer(Abadoned_greatworkBlocks.WAVE_TRANSMITTER.value) { lines, state, pos, _, world, _ ->
            val be = world.getBlockEntity(pos) as? WaveTransmitterBlockEntity ?: return@addDisplayer
            be.appendLensLines(lines, state)
        }
        ScryingLensOverlayRegistry.addDisplayer(Abadoned_greatworkBlocks.WAVE_REPEATER.value) { lines, _, pos, _, world, _ ->
            val be = world.getBlockEntity(pos) as? WaveRepeaterBlockEntity ?: return@addDisplayer
            be.appendLensLines(lines)
        }
        ScryingLensOverlayRegistry.addDisplayer(Abadoned_greatworkBlocks.MEDIA_POOL.value) { lines, _, pos, _, world, _ ->
            val be = world.getBlockEntity(pos) as? MediaPoolBlockEntity ?: return@addDisplayer
            be.appendLensLines(lines)
        }
        ScryingLensOverlayRegistry.addDisplayer(Abadoned_greatworkBlocks.AMBIT_SWITCH.value) { lines, _, pos, _, world, _ ->
            val be = world.getBlockEntity(pos) as? AmbitSwitchBlockEntity ?: return@addDisplayer
            be.appendLensLines(lines)
        }
        ScryingLensOverlayRegistry.addDisplayer(Abadoned_greatworkBlocks.AMBIT_ANCHOR.value) { lines, _, pos, _, world, _ ->
            val be = world.getBlockEntity(pos) as? AmbitAnchorBlockEntity ?: return@addDisplayer
            be.appendLensLines(lines)
        }
    }
}
