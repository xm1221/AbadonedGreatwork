package cn.xm1221.abadoned_greatwork.client.ponder

import cn.xm1221.MieLib.worker.DrawPatternWorker
import kotlin.math.roundToInt

/**
 * 思索界面的图案颜色覆盖状态，仅在 [HexPonderScreen] 渲染当前演示/沙盒画布期间激活。
 * 通过 MieLib 的 [cn.xm1221.MieLib.registry.DrawPatternHook] 注入颜色，避免重复 Mixin `GuiSpellcasting.render`。
 */
object PonderPatternColorState {
    @Volatile
    var active: Boolean = false

    private val colorOverrides: MutableMap<Int, Pair<Int, Int>> = HashMap()

    fun begin(entries: Map<Int, Pair<Int, Int>>) {
        colorOverrides.clear()
        colorOverrides.putAll(entries)
        active = colorOverrides.isNotEmpty()
    }

    fun end() {
        active = false
        colorOverrides.clear()
    }

    fun lookup(index: Int): Pair<Int, Int>? = colorOverrides[index]

    /** 根据主色生成尾→头渐变（略微提亮头部），保留 Hex 原生流光层次感。 */
    fun tailHeadFromColor(argb: Int): Pair<Int, Int> {
        val a = argb ushr 24 and 0xFF
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val tr = (r * 0.82f).roundToInt().coerceIn(0, 255)
        val tg = (g * 0.82f).roundToInt().coerceIn(0, 255)
        val tb = (b * 0.82f).roundToInt().coerceIn(0, 255)
        val hr = (r + (255 - r) * 0.25f).roundToInt().coerceIn(0, 255)
        val hg = (g + (255 - g) * 0.25f).roundToInt().coerceIn(0, 255)
        val hb = (b + (255 - b) * 0.25f).roundToInt().coerceIn(0, 255)
        val tail = (a shl 24) or (tr shl 16) or (tg shl 8) or tb
        val head = (a shl 24) or (hr shl 16) or (hg shl 8) or hb
        return tail to head
    }
}

/**
 * 注册到 MieLib `DrawPatternHook` 的思索演示染色 Worker。
 */
object PonderDrawPatternWorker : DrawPatternWorker(priority = 50) {

    override fun ifEffect(result: DrawResult, seed: Double): Boolean {
        if (!PonderPatternColorState.active || seed.isNaN()) return false
        return PonderPatternColorState.lookup(seed.roundToInt()) != null
    }

    override fun effect(drawResult: DrawResult, seed: Double): DrawResult {
        val pair = PonderPatternColorState.lookup(seed.roundToInt()) ?: return drawResult
        return DrawResult(drawResult.drawLast, pair.first, pair.second, drawResult.flowIrregular)
    }
}
