package cn.xm1221.abadoned_greatwork.client.ponder.stage

import cn.xm1221.abadoned_greatwork.api.tianwen.TianwenApi
import cn.xm1221.abadoned_greatwork.api.tianwen.TianwenStageRenderer
import cn.xm1221.abadoned_greatwork.client.ponder.PonderGuiDraw
import cn.xm1221.abadoned_greatwork.ponder.data.StepSnapshot
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * 内置「2D 物品交互 / 合成流程（interaction_2d）」舞台渲染器。
 *
 * 适合展示：手持道具右键方块/物品、合成/注魔转换、多步骤流程卡片，并可随时在下一幕无缝切换到 `hex_grid` 或 `world_3d`。
 */
class Interaction2DStageRenderer : TianwenStageRenderer {

    override val id: String = TianwenApi.STAGE_INTERACTION_2D
    override val hasLeftPanel: Boolean = false
    override val supportsSandbox: Boolean = false

    private data class SlotEntry(
        val itemId: String,
        val count: Int,
        val label: String,
    )

    private var hoveredStack: ItemStack? = null

    override fun renderStage(
        mc: Minecraft,
        font: Font,
        g: GuiGraphics,
        innerX: Int,
        innerY: Int,
        innerW: Int,
        innerH: Int,
        snapshots: List<StepSnapshot>,
        currentStepIndex: Int,
        stepTicks: Float,
        partialTick: Float,
        playing: Boolean,
        speed: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        hoveredStack = null
        val snap = snapshots.getOrNull(currentStepIndex) ?: return
        val obj = runCatching { JsonParser.parseString(snap.stageDataJson).asJsonObject }.getOrNull() ?: return

        val inputs = parseSlots(obj.getAsJsonArray("inputs"))
        val outputs = parseSlots(obj.getAsJsonArray("outputs"))
        val actionLabel = localize(obj.get("action_label")?.asString ?: "")
        val caption = localize(obj.get("caption")?.asString ?: "")

        val prog = ((stepTicks + if (playing) partialTick * speed else 0f) / snap.strokeTicks.coerceAtLeast(1)).coerceIn(0f, 1f)

        val slotSize = 28
        val gap = 8
        val inW = if (inputs.isEmpty()) 0 else inputs.size * slotSize + (inputs.size - 1) * gap
        val outW = if (outputs.isEmpty()) 0 else outputs.size * slotSize + (outputs.size - 1) * gap
        val arrowW = 68
        val totalW = inW + arrowW + outW
        val startX = innerX + (innerW - totalW) / 2
        val centerY = innerY + innerH / 2 - 6

        // 输入物品槽
        var cx = startX
        for (slot in inputs) {
            drawItemSlot(g, font, cx, centerY - slotSize / 2, slotSize, slot, mouseX, mouseY, PonderGuiDraw.TEXT_CYAN)
            cx += slotSize + gap
        }

        // 中央转换箭头 + 交互动作说明
        val arrowX = startX + inW + 6
        val arrowY = centerY - 2
        val barW = arrowW - 12
        PonderGuiDraw.drawRecessedBox(g, arrowX, arrowY, barW, 5)
        val fillW = (barW * prog).toInt().coerceIn(0, barW)
        if (fillW > 1) {
            g.fill(arrowX + 1, arrowY + 1, arrowX + fillW - 1, arrowY + 4, PonderGuiDraw.AMETHYST_BRIGHT)
        }
        // 箭头尖端
        g.drawString(font, "▶", arrowX + barW - 2, arrowY - 2, if (prog >= 0.99f) PonderGuiDraw.TEXT_GOLD else PonderGuiDraw.TEXT_MUTED, true)

        if (actionLabel.isNotEmpty()) {
            val lw = font.width(actionLabel)
            g.drawString(font, actionLabel, arrowX + (barW - lw) / 2, centerY - 22, PonderGuiDraw.TEXT_GOLD, true)
        }

        // 输出物品槽
        var ox = startX + inW + arrowW
        for (slot in outputs) {
            val highlight = if (prog >= 0.95f) PonderGuiDraw.TEXT_GOLD else PonderGuiDraw.AMETHYST_DARK
            drawItemSlot(g, font, ox, centerY - slotSize / 2, slotSize, slot, mouseX, mouseY, highlight)
            ox += slotSize + gap
        }

        if (caption.isNotEmpty()) {
            val cw = font.width(caption) + 14
            val capX = innerX + (innerW - cw) / 2
            val capY = centerY + 34
            PonderGuiDraw.drawRecessedBox(g, capX, capY, cw, 16, 0xCC120E1D.toInt())
            g.drawString(font, caption, capX + 7, capY + 4, PonderGuiDraw.TEXT_BODY, true)
        }
    }

    override fun renderOverlayTooltip(mc: Minecraft, font: Font, g: GuiGraphics, mouseX: Int, mouseY: Int) {
        hoveredStack?.let { stack ->
            g.renderTooltip(font, stack, mouseX, mouseY)
        }
    }

    private fun drawItemSlot(
        g: GuiGraphics,
        font: Font,
        x: Int,
        y: Int,
        size: Int,
        slot: SlotEntry,
        mouseX: Int,
        mouseY: Int,
        borderColor: Int,
    ) {
        PonderGuiDraw.drawRecessedBox(g, x, y, size, size, 0xFF141020.toInt(), borderColor)
        val stack = PonderGuiDraw.resolveItemStack(slot.itemId)
        if (!stack.isEmpty) {
            val itemX = x + (size - 16) / 2
            val itemY = y + (size - 16) / 2
            g.renderItem(stack, itemX, itemY)
            if (slot.count > 1) {
                val cnt = slot.count.toString()
                g.drawString(font, cnt, x + size - font.width(cnt) - 2, y + size - font.lineHeight, PonderGuiDraw.TEXT_MAIN, true)
            }
            if (mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size) {
                hoveredStack = stack.copy().apply { count = slot.count }
            }
        }
        if (slot.label.isNotEmpty()) {
            val lbl = localize(slot.label)
            val lw = font.width(lbl)
            g.drawString(font, lbl, x + (size - lw) / 2, y + size + 4, PonderGuiDraw.TEXT_MUTED, false)
        }
    }

    private fun parseSlots(arr: com.google.gson.JsonArray?): List<SlotEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<SlotEntry>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val item = o.get("item")?.asString ?: continue
            val count = o.get("count")?.asInt ?: 1
            val label = o.get("label")?.asString ?: ""
            out.add(SlotEntry(item, count.coerceAtLeast(1), label))
        }
        return out
    }

    private fun localize(s: String): String =
        if (s.isNotEmpty() && I18n.exists(s)) I18n.get(s) else s
}
