package cn.xm1221.abadoned_greatwork.client.ponder

import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.client.ScryingLensOverlayRegistry
import at.petrak.hexcasting.common.blocks.circles.BlockEntitySlate
import at.petrak.hexcasting.common.blocks.circles.BlockSlate
import at.petrak.hexcasting.common.entities.EntityWallScroll
import at.petrak.hexcasting.common.lib.HexAttributes
import at.petrak.hexcasting.common.lib.HexBlocks
import at.petrak.hexcasting.common.lib.HexItems
import com.mojang.datafixers.util.Pair
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * 问天统一快捷键（默认 `G`）与探知透镜“按图案搜索”触发器：
 * - 佩戴探知透镜查看石板或墙挂卷轴上的图案时显示“按 [G] 问天：用此图案检索”；
 * - 按 `G` 时：若正凝视带图案对象，直接打开目录并预填入该图案搜索（同时列出匹配的问天章节与《咒术笔记》页码）；
 *   否则直接打开「问天」章节与《咒术笔记》检索目录。
 */
object PonderScryingTrigger {

    @Volatile
    private var currentScryPattern: HexPattern? = null

    @Volatile
    private var currentScryActionId: ResourceLocation? = null

    fun init() {
        registerSlateOverlay()
        ClientGuiEvent.RENDER_HUD.register { graphics, _ ->
            onHudRender(graphics)
        }
        ClientTickEvent.CLIENT_POST.register { mc ->
            while (PonderKeybinds.OPEN_TIANWEN.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    val pat = currentScryPattern
                    if (pat != null) {
                        PonderClientState.openIndexWithPattern(pat, currentScryActionId, null)
                    } else {
                        PonderClientState.openIndex(null)
                    }
                }
            }
        }
    }

    private fun registerSlateOverlay() {
        try {
            ScryingLensOverlayRegistry.addDisplayer(HexBlocks.SLATE) { lines, _, pos, _, world, _ ->
                val tile = world.getBlockEntity(pos)
                if (tile is BlockEntitySlate) {
                    val pattern = tile.pattern ?: return@addDisplayer
                    val actionId = PonderClientState.findActionId(pattern)
                    if (actionId != null) {
                        lines.add(
                            Pair.of(
                                ItemStack(HexBlocks.SLATE.asItem()),
                                Component.translatable("hexcasting.action.$actionId"),
                            )
                        )
                    } else {
                        lines.add(
                            Pair.of(
                                ItemStack(HexBlocks.SLATE.asItem()),
                                Component.translatable("block.hexcasting.slate.written"),
                            )
                        )
                    }
                    lines.add(
                        Pair.of(
                            ItemStack.EMPTY,
                            Component.translatable(
                                "hud.abadoned_greatwork.scry_search",
                                PonderKeybinds.OPEN_TIANWEN.translatedKeyMessage,
                            ),
                        )
                    )
                }
            }
        } catch (_: IllegalArgumentException) {
            // 若其他模组已登记石板 displayer，则通过 HUD 帧回调继续追踪图案
        }
    }

    private fun onHudRender(graphics: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { clearTarget(); return }
        val level = mc.level ?: run { clearTarget(); return }

        if (player.getAttributeValue(HexAttributes.SCRY_SIGHT) <= 0.0 ||
            player.getAttributeValue(HexAttributes.FEEBLE_MIND) > 0.0
        ) {
            clearTarget()
            return
        }

        val hitRes = mc.hitResult ?: run { clearTarget(); return }
        when (hitRes.type) {
            HitResult.Type.ENTITY -> {
                val entity = (hitRes as EntityHitResult).entity
                if (entity is EntityWallScroll) {
                    val pattern = entity.pattern
                    if (pattern != null) {
                        val actionId = PonderClientState.findActionId(pattern)
                        currentScryPattern = pattern
                        currentScryActionId = actionId
                        renderWallScrollOverlay(graphics, mc, actionId)
                        return
                    }
                }
                clearTarget()
            }
            HitResult.Type.BLOCK -> {
                val pos = (hitRes as BlockHitResult).blockPos
                val state = level.getBlockState(pos)
                if (state.block is BlockSlate) {
                    val tile = level.getBlockEntity(pos)
                    val pattern = (tile as? BlockEntitySlate)?.pattern
                    if (pattern != null) {
                        currentScryPattern = pattern
                        currentScryActionId = PonderClientState.findActionId(pattern)
                    } else {
                        clearTarget()
                    }
                } else {
                    clearTarget()
                }
            }
            else -> clearTarget()
        }
    }

    private fun clearTarget() {
        currentScryPattern = null
        currentScryActionId = null
    }

    private fun renderWallScrollOverlay(
        graphics: GuiGraphics,
        mc: Minecraft,
        actionId: ResourceLocation?,
    ) {
        val lines = ArrayList<Pair<ItemStack, Component>>()
        val titleComp = if (actionId != null) {
            Component.translatable("hexcasting.action.$actionId")
        } else {
            Component.translatable("entity.hexcasting.wall_scroll")
        }
        lines.add(Pair.of(ItemStack(HexItems.SCROLL_LARGE), titleComp))
        lines.add(
            Pair.of(
                ItemStack.EMPTY,
                Component.translatable(
                    "hud.abadoned_greatwork.scry_search",
                    PonderKeybinds.OPEN_TIANWEN.translatedKeyMessage,
                ),
            )
        )

        val window = mc.window
        val maxWidth = (window.guiScaledWidth / 2f * 0.8f).toInt().coerceAtLeast(80)
        var totalHeight = 8
        val formatted = ArrayList<Pair<ItemStack, List<FormattedText>>>(lines.size)
        for (pair in lines) {
            totalHeight += mc.font.lineHeight + 6
            val wrapped = mc.font.splitter.splitLines(pair.second, maxWidth, Style.EMPTY)
            formatted.add(Pair.of(pair.first, wrapped))
            if (wrapped.size > 1) {
                totalHeight += mc.font.lineHeight * (wrapped.size - 1)
            }
        }

        val ps = graphics.pose()
        ps.pushPose()
        ps.translate(window.guiScaledWidth / 2.0 + 8.0, window.guiScaledHeight / 2.0 - totalHeight, 0.0)
        for (pair in formatted) {
            val stack = pair.first
            if (!stack.isEmpty) {
                graphics.renderItem(stack, 0, 0)
            }
            val tx = if (stack.isEmpty) 0 else 18
            for (line in pair.second) {
                val vis = Language.getInstance().getVisualOrder(line)
                graphics.drawString(mc.font, vis, tx, 5, 0xFFFFFFFF.toInt(), true)
                ps.translate(0.0, mc.font.lineHeight.toDouble(), 0.0)
            }
            if (pair.second.isEmpty()) {
                ps.translate(0.0, mc.font.lineHeight.toDouble(), 0.0)
            }
            ps.translate(0.0, 6.0, 0.0)
        }
        ps.popPose()
    }
}
