package cn.xm1221.abadoned_greatwork.client.ponder

import at.petrak.hexcasting.common.lib.HexItems
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.roundToInt

/**
 * 原生 Minecraft + Hex Casting 像素风格绘制工具集（严格 1× 整数网格对齐，避免通用网页风）。
 *
 * 调色基线取自 Hex 石板、紫水晶、深板岩与原版界面：
 * - 外轮廓黑：`#FF08060D`
 * - 面板底：`#FF1A1525`（高光斜面 `#FF493C66`，阴影斜面 `#FF0F0B17`）
 * - 演示施法舞台：独立深邃板岩框 `#FF221B31` + 4 枚紫晶角钉 `#FFB482FF` + 内凹暗场渐变 `#FF141020 → #FF0B0813`
 */
object PonderGuiDraw {

    const val OUTLINE = 0xFF08060D.toInt()
    const val PANEL_BG = 0xEE191423.toInt()
    const val PANEL_BEVEL_LIGHT = 0xFF4B3D69.toInt()
    const val PANEL_BEVEL_DARK = 0xFF0E0B15.toInt()

    const val SLOT_BG = 0xFF100C19.toInt()
    const val SLOT_BEVEL_DARK = 0xFF07050B.toInt()
    const val SLOT_BEVEL_LIGHT = 0xFF342A4A.toInt()

    const val STAGE_FRAME_BODY = 0xFF231C32.toInt()
    const val STAGE_FRAME_LIGHT = 0xFF635287.toInt()
    const val STAGE_FRAME_DARK = 0xFF120D1C.toInt()
    const val STAGE_BG_TOP = 0xFF141021.toInt()
    const val STAGE_BG_BOTTOM = 0xFF0A0812.toInt()

    const val AMETHYST_BRIGHT = 0xFFB886FF.toInt()
    const val AMETHYST_DARK = 0xFF6A3FB5.toInt()
    const val SANDBOX_ACCENT = 0xFF5CC47E.toInt()

    const val TEXT_MAIN = 0xFFF2EEFA.toInt()
    const val TEXT_BODY = 0xFFD4CBE8.toInt()
    const val TEXT_MUTED = 0xFF9C8FC0.toInt()
    const val TEXT_GOLD = 0xFFFFD93D.toInt()
    const val TEXT_CYAN = 0xFF64C8FF.toInt()
    const val TEXT_ERR = 0xFFFF6B6B.toInt()

    private val ITEM_CACHE = HashMap<String, ItemStack>()

    /** 从注册名解析物品栈用于贴图图标与画笔光标；支持空串 / `"none"` 隐藏。 */
    fun resolveItemStack(itemId: String): ItemStack {
        val trimmed = itemId.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true)) {
            return ItemStack.EMPTY
        }
        return ITEM_CACHE.getOrPut(trimmed) {
            val rl = ResourceLocation.tryParse(trimmed)
            val item = if (rl != null) BuiltInRegistries.ITEM.get(rl) else Items.AIR
            if (item == Items.AIR) ItemStack(HexItems.STAFF_OAK.get()) else ItemStack(item)
        }
    }

    /** 绘制原版风格 1px 凸起斜面像素面板。 */
    fun drawBeveledPanel(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, bg: Int = PANEL_BG) {
        if (w < 4 || h < 4) return
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg)
        // 1px 外轮廓
        g.fill(x, y, x + w, y + 1, OUTLINE)
        g.fill(x, y + h - 1, x + w, y + h, OUTLINE)
        g.fill(x, y, x + 1, y + h, OUTLINE)
        g.fill(x + w - 1, y, x + w, y + h, OUTLINE)
        // 1px 左上受光 / 右下背光内斜面
        g.fill(x + 1, y + 1, x + w - 1, y + 2, PANEL_BEVEL_LIGHT)
        g.fill(x + 1, y + 1, x + 2, y + h - 1, PANEL_BEVEL_LIGHT)
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PANEL_BEVEL_DARK)
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, PANEL_BEVEL_DARK)
    }

    /** 绘制内凹像素槽位（用于施法栈项、进度条轨道、控制台）。 */
    fun drawRecessedBox(
        g: GuiGraphics,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        bg: Int = SLOT_BG,
        borderHighlight: Int = 0,
    ) {
        if (w < 3 || h < 3) return
        g.fill(x, y, x + w, y + h, bg)
        val topLeft = if (borderHighlight != 0) borderHighlight else SLOT_BEVEL_DARK
        val botRight = if (borderHighlight != 0) borderHighlight else SLOT_BEVEL_LIGHT
        g.fill(x, y, x + w, y + 1, topLeft)
        g.fill(x, y, x + 1, y + h, topLeft)
        g.fill(x, y + h - 1, x + w, y + h, botRight)
        g.fill(x + w - 1, y, x + w, y + h, botRight)
    }

    /** 绘制带边框与石板斜面的独立演示施法舞台框，清晰区隔画布区与周围控件。 */
    fun drawCastingStageFrame(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, sandboxMode: Boolean) {
        if (w < 16 || h < 16) return
        // 内部深邃暗紫板岩渐变底衬
        g.fillGradient(x + 4, y + 4, x + w - 4, y + h - 4, STAGE_BG_TOP, STAGE_BG_BOTTOM)

        // 4px 厚石板镶边主框
        g.fill(x + 1, y + 1, x + w - 1, y + 4, STAGE_FRAME_BODY)
        g.fill(x + 1, y + h - 4, x + w - 1, y + h - 1, STAGE_FRAME_BODY)
        g.fill(x + 1, y + 4, x + 4, y + h - 4, STAGE_FRAME_BODY)
        g.fill(x + w - 4, y + 4, x + w - 1, y + h - 4, STAGE_FRAME_BODY)

        // 最外圈黑边
        g.fill(x, y, x + w, y + 1, OUTLINE)
        g.fill(x, y + h - 1, x + w, y + h, OUTLINE)
        g.fill(x, y, x + 1, y + h, OUTLINE)
        g.fill(x + w - 1, y, x + w, y + h, OUTLINE)

        // 外斜面
        g.fill(x + 1, y + 1, x + w - 1, y + 2, STAGE_FRAME_LIGHT)
        g.fill(x + 1, y + 1, x + 2, y + h - 1, STAGE_FRAME_LIGHT)
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, STAGE_FRAME_DARK)
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, STAGE_FRAME_DARK)

        // 内凹镶边（沙盒模式微泛绿意以示可交互）
        val innerAccent = if (sandboxMode) SANDBOX_ACCENT else AMETHYST_DARK
        g.fill(x + 3, y + 3, x + w - 3, y + 4, innerAccent)
        g.fill(x + 3, y + h - 4, x + w - 3, y + h - 3, innerAccent)
        g.fill(x + 3, y + 3, x + 4, y + h - 3, innerAccent)
        g.fill(x + w - 4, y + 3, x + w - 3, y + h - 3, innerAccent)

        // 四角 3×3 紫水晶铆钉装饰
        val rivet = if (sandboxMode) SANDBOX_ACCENT else AMETHYST_BRIGHT
        drawRivet(g, x + 1, y + 1, rivet)
        drawRivet(g, x + w - 4, y + 1, rivet)
        drawRivet(g, x + 1, y + h - 4, rivet)
        drawRivet(g, x + w - 4, y + h - 4, rivet)
    }

    private fun drawRivet(g: GuiGraphics, rx: Int, ry: Int, bright: Int) {
        g.fill(rx, ry, rx + 3, ry + 3, OUTLINE)
        g.fill(rx, ry, rx + 2, ry + 2, bright)
    }

    /** 绘制原版风像素按钮。 */
    fun drawPixelButton(
        g: GuiGraphics,
        font: Font,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String,
        hovered: Boolean,
        active: Boolean = false,
        enabled: Boolean = true,
        accent: Int = AMETHYST_BRIGHT,
    ) {
        val bg = when {
            !enabled -> 0xFF14111C.toInt()
            active -> 0xFF33244F.toInt()
            hovered -> 0xFF2D2342.toInt()
            else -> 0xFF211A30.toInt()
        }
        val topBorder = when {
            !enabled -> 0xFF282236.toInt()
            active -> accent
            hovered -> 0xFF7B63AE.toInt()
            else -> PANEL_BEVEL_LIGHT
        }
        val botBorder = if (active) accent else PANEL_BEVEL_DARK
        g.fill(x, y, x + w, y + h, OUTLINE)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg)
        g.fill(x + 1, y + 1, x + w - 1, y + 2, topBorder)
        g.fill(x + 1, y + 1, x + 2, y + h - 1, topBorder)
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, botBorder)
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, botBorder)

        val textColor = when {
            !enabled -> 0xFF6B6280.toInt()
            active -> TEXT_GOLD
            hovered -> 0xFFFFFFFF.toInt()
            else -> TEXT_MAIN
        }
        val tw = font.width(label)
        val drawX = x + (w - tw) / 2
        val drawY = y + (h - font.lineHeight) / 2 + 1
        g.drawString(font, label, drawX, drawY, textColor, true)
    }

    /**
     * 在屏幕顶层绘制带正交像素引线的浮动提示气泡（遵循原生 Tooltip 双线描边语法）。
     */
    fun drawCalloutOverlay(
        g: GuiGraphics,
        font: Font,
        boxX: Int,
        boxY: Int,
        boxW: Int,
        lines: List<FormattedCharSequence>,
        iotaDisplays: List<Pair<FormattedCharSequence, Int>>,
        targetX: Int,
        targetY: Int,
        drawLeader: Boolean,
        accentColor: Int,
    ) {
        val lineCount = lines.size.coerceAtLeast(1)
        val iotaRowsHeight = if (iotaDisplays.isEmpty()) 0 else 6 + iotaDisplays.size * (font.lineHeight + 3)
        val boxH = 10 + lineCount * (font.lineHeight + 2) + iotaRowsHeight

        g.flush()
        val pose = g.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 310f)
        RenderSystem.disableDepthTest()
        try {
            if (drawLeader) {
                val anchorX = if (targetX < boxX) boxX else if (targetX > boxX + boxW) boxX + boxW else boxX + boxW / 2
                val anchorY = boxY + boxH / 2
                val elbowX = ((targetX + anchorX) / 2)
                // 1px 正交像素引线（外黑阴影 + 内彩色线）
                drawOrthoLine(g, targetX, targetY, elbowX, anchorY, anchorX, accentColor)
                // 目标锚点 3×3 方块
                g.fill(targetX - 2, targetY - 2, targetX + 3, targetY + 3, OUTLINE)
                g.fill(targetX - 1, targetY - 1, targetX + 2, targetY + 2, accentColor)
            }

            // 原版 Tooltip 双层像素边框（外 1px 黑 + 内 1px 渐变/主题紫边 + 左侧强调条）
            g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF4120E1D.toInt())
            g.fill(boxX, boxY, boxX + boxW, boxY + 1, OUTLINE)
            g.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, OUTLINE)
            g.fill(boxX, boxY, boxX + 1, boxY + boxH, OUTLINE)
            g.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, OUTLINE)

            g.fill(boxX + 1, boxY + 1, boxX + boxW - 1, boxY + 2, accentColor)
            g.fill(boxX + 1, boxY + boxH - 2, boxX + boxW - 1, boxY + boxH - 1, AMETHYST_DARK)
            g.fill(boxX + 1, boxY + 1, boxX + 3, boxY + boxH - 1, accentColor)
            g.fill(boxX + boxW - 2, boxY + 1, boxX + boxW - 1, boxY + boxH - 1, AMETHYST_DARK)

            var cy = boxY + 5
            for (line in lines) {
                g.drawString(font, line, boxX + 7, cy, TEXT_MAIN, true)
                cy += font.lineHeight + 2
            }
            if (iotaDisplays.isNotEmpty()) {
                cy += 3
                for ((seq, col) in iotaDisplays) {
                    val chipH = font.lineHeight + 2
                    drawRecessedBox(g, boxX + 7, cy - 1, boxW - 14, chipH, 0xFF0C0914.toInt(), col)
                    g.drawString(font, seq, boxX + 11, cy, col, true)
                    cy += chipH + 1
                }
            }
            g.flush()
        } finally {
            RenderSystem.enableDepthTest()
            pose.popPose()
        }
    }

    private fun drawOrthoLine(
        g: GuiGraphics,
        x0: Int,
        y0: Int,
        elbowX: Int,
        y1: Int,
        x1: Int,
        color: Int,
    ) {
        val minX1 = minOf(x0, elbowX)
        val maxX1 = maxOf(x0, elbowX) + 1
        val minY = minOf(y0, y1)
        val maxY = maxOf(y0, y1) + 1
        val minX2 = minOf(elbowX, x1)
        val maxX2 = maxOf(elbowX, x1) + 1
        val lineCol = (0xCC shl 24) or (color and 0xFFFFFF)

        g.fill(minX1, y0, maxX1, y0 + 1, lineCol)
        g.fill(elbowX, minY, elbowX + 1, maxY, lineCol)
        g.fill(minX2, y1, maxX2, y1 + 1, lineCol)
    }

    /**
     * 在当前笔尖坐标 `(tipX, tipY)` 处渲染可配置物品光标（默认为法杖贴图）。
     * 原版法杖/工具贴图朝向右上角，故将物品左上角摆在 `(tipX - 14, tipY - 2)` 使杖尖正好对准笔尖。
     */
    fun renderItemCursor(g: GuiGraphics, stack: ItemStack, tipX: Float, tipY: Float, color: Int) {
        val ix = tipX.roundToInt()
        val iy = tipY.roundToInt()

        val pose = g.pose()
        pose.pushPose()
        // 保持笔尖与物品光标在演示图层之上，同时低于浮窗提示图层（Z = 310）
        pose.translate(0f, 0f, 50f)
        try {
            // 笔尖 3×3 高亮光点
            g.fill(ix - 2, iy - 2, ix + 2, iy + 2, (0x88 shl 24) or (color and 0xFFFFFF))
            g.fill(ix - 1, iy - 1, ix + 1, iy + 1, 0xFFFFFFFF.toInt())

            if (!stack.isEmpty) {
                g.renderItem(stack, ix - 14, iy - 2)
            }
        } finally {
            pose.popPose()
        }
    }

    /** 绘制 7×8 原版像素风小锁徽章（用于未解锁章节卡片）。 */
    fun drawLockBadge(g: GuiGraphics, x: Int, y: Int) {
        // 锁梁（灰铁色）
        g.fill(x + 2, y, x + 5, y + 1, 0xFF9C8FC0.toInt())
        g.fill(x + 1, y + 1, x + 2, y + 3, 0xFF9C8FC0.toInt())
        g.fill(x + 5, y + 1, x + 6, y + 3, 0xFF9C8FC0.toInt())
        // 锁身（外黑框 + 暗金填充）
        g.fill(x, y + 3, x + 7, y + 8, OUTLINE)
        g.fill(x + 1, y + 4, x + 6, y + 7, 0xFFD99B26.toInt())
        // 钥匙孔
        g.fill(x + 3, y + 5, x + 4, y + 7, OUTLINE)
    }
}
