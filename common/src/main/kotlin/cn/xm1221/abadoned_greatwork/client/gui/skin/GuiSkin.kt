package cn.xm1221.abadoned_greatwork.client.gui.skin

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import java.util.concurrent.ConcurrentHashMap

data class SkinRect(val x: Int, val y: Int, val w: Int, val h: Int)

data class UvRect(val u: Int, val v: Int, val w: Int, val h: Int)

data class NineSlice(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class SpriteDef(
    val uv: UvRect? = null,
    val nineSlice: NineSlice? = null,
    val states: Map<String, UvRect> = emptyMap(),
) {
    fun resolve(state: String?): UvRect? =
        if (state != null) states[state] ?: uv else uv
}

/**
 * 贴图驱动 GUI 皮肤定义：由资源包中的 `assets/abadoned_greatwork/gui_skins/<name>.json` 加载。
 * 支持 `"extends"` 继承公共精灵、色板与局部区域覆盖。
 */
data class GuiSkin(
    val name: String,
    val texture: ResourceLocation,
    val texWidth: Int,
    val texHeight: Int,
    val sprites: Map<String, SpriteDef>,
    val layoutWidth: Int,
    val layoutHeight: Int,
    val regions: Map<String, SkinRect>,
    val colors: Map<String, Int>,
) {
    fun region(key: String, default: SkinRect = SkinRect(0, 0, 80, 20)): SkinRect =
        regions[key] ?: default

    fun color(key: String, default: Int): Int = colors[key] ?: default

    companion object {
        fun emptyFallback(name: String): GuiSkin = GuiSkin(
            name = name,
            texture = Abadoned_greatwork.id("textures/gui/notes/atlas.png"),
            texWidth = 512,
            texHeight = 256,
            sprites = emptyMap(),
            layoutWidth = 396,
            layoutHeight = 230,
            regions = emptyMap(),
            colors = emptyMap(),
        )

        fun fromJson(name: String, root: JsonObject, parent: GuiSkin? = null): GuiSkin {
            val base = parent ?: emptyFallback(name)

            val texture = root.get("texture")?.asString?.let { ResourceLocation.tryParse(it) } ?: base.texture
            val texSizeArr = root.getAsJsonArray("tex_size")
            val texW = if (texSizeArr != null && texSizeArr.size() >= 2) texSizeArr[0].asInt else base.texWidth
            val texH = if (texSizeArr != null && texSizeArr.size() >= 2) texSizeArr[1].asInt else base.texHeight

            val sprites = base.sprites.toMutableMap()
            root.getAsJsonObject("sprites")?.entrySet()?.forEach { (k, v) ->
                if (v.isJsonObject) {
                    val obj = v.asJsonObject
                    val uv = parseUv(obj.getAsJsonArray("uv"))
                    val nine = parseNineSlice(obj.getAsJsonArray("nine_slice"))
                    val states = mutableMapOf<String, UvRect>()
                    obj.getAsJsonObject("states")?.entrySet()?.forEach { (sk, sv) ->
                        if (sv.isJsonArray) {
                            parseUv(sv.asJsonArray)?.let { states[sk] = it }
                        }
                    }
                    sprites[k] = SpriteDef(uv, nine, states)
                }
            }

            val layoutObj = root.getAsJsonObject("layout")
            val sizeArr = layoutObj?.getAsJsonArray("size")
            val layoutW = if (sizeArr != null && sizeArr.size() >= 2) sizeArr[0].asInt else base.layoutWidth
            val layoutH = if (sizeArr != null && sizeArr.size() >= 2) sizeArr[1].asInt else base.layoutHeight
            val regions = base.regions.toMutableMap()
            layoutObj?.getAsJsonObject("regions")?.entrySet()?.forEach { (rk, rv) ->
                if (rv.isJsonArray && rv.asJsonArray.size() >= 4) {
                    val a = rv.asJsonArray
                    regions[rk] = SkinRect(a[0].asInt, a[1].asInt, a[2].asInt, a[3].asInt)
                }
            }

            val colors = base.colors.toMutableMap()
            root.getAsJsonObject("colors")?.entrySet()?.forEach { (ck, cv) ->
                if (cv.isJsonPrimitive) {
                    parseHexColor(cv.asString)?.let { colors[ck] = it }
                }
            }

            return GuiSkin(name, texture, texW, texH, sprites, layoutW, layoutH, regions, colors)
        }

        /** 将子皮肤叠加到基础皮肤上（子皮肤提供同名 key 时覆盖父级） */
        fun merge(base: GuiSkin, overlay: GuiSkin): GuiSkin = GuiSkin(
            name = overlay.name,
            texture = overlay.texture,
            texWidth = overlay.texWidth,
            texHeight = overlay.texHeight,
            sprites = base.sprites + overlay.sprites,
            layoutWidth = overlay.layoutWidth,
            layoutHeight = overlay.layoutHeight,
            regions = base.regions + overlay.regions,
            colors = base.colors + overlay.colors,
        )

        private fun parseUv(arr: JsonArray?): UvRect? {
            if (arr == null || arr.size() < 4) return null
            return UvRect(arr[0].asInt, arr[1].asInt, arr[2].asInt, arr[3].asInt)
        }

        private fun parseNineSlice(arr: JsonArray?): NineSlice? {
            if (arr == null || arr.size() < 4) return null
            return NineSlice(arr[0].asInt, arr[1].asInt, arr[2].asInt, arr[3].asInt)
        }

        fun parseHexColor(raw: String): Int? {
            val hex = raw.trim().removePrefix("#")
            return runCatching {
                when (hex.length) {
                    6 -> (0xFF shl 24) or hex.toInt(16)
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/**
 * 基于 [GuiSkin] 的九宫格与多状态贴图渲染器。
 * 缺失精灵或贴图缺失时绘制醒目的品红/黑棋盘格并记录一次性警告，便于资源包制作排查。
 */
object SkinRenderer {

    private val warnedMissing = ConcurrentHashMap.newKeySet<String>()
    private val texturePresenceCache = ConcurrentHashMap<ResourceLocation, Boolean>()

    fun clearTextureCache() {
        texturePresenceCache.clear()
        warnedMissing.clear()
    }

    private fun isTexturePresent(tex: ResourceLocation): Boolean =
        texturePresenceCache.computeIfAbsent(tex) {
            Minecraft.getInstance().resourceManager.getResource(it).isPresent
        }

    fun drawSprite(
        g: GuiGraphics,
        skin: GuiSkin,
        key: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        state: String? = null,
    ) {
        if (w <= 0 || h <= 0) return
        val sprite = skin.sprites[key]
        val uv = sprite?.resolve(state)
        if (sprite == null || uv == null || !isTexturePresent(skin.texture)) {
            val warnKey = "${skin.name}:$key:$state:${skin.texture}"
            if (warnedMissing.add(warnKey)) {
                Abadoned_greatwork.LOGGER.warn("Missing GUI skin sprite or texture: {}", warnKey)
            }
            drawMissingCheckerboard(g, x, y, w, h)
            return
        }
        val nine = sprite.nineSlice
        if (nine != null) {
            drawNineSlice(g, skin.texture, skin.texWidth, skin.texHeight, x, y, w, h, uv, nine)
        } else {
            g.blit(skin.texture, x, y, w, h, uv.u.toFloat(), uv.v.toFloat(), uv.w, uv.h, skin.texWidth, skin.texHeight)
        }
    }

    fun drawDashedHLine(g: GuiGraphics, x: Int, y: Int, w: Int, color: Int, dash: Int = 3, gap: Int = 2) {
        var cx = x
        val endX = x + w
        while (cx < endX) {
            val seg = minOf(dash, endX - cx)
            g.fill(cx, y, cx + seg, y + 1, color)
            cx += dash + gap
        }
    }

    fun drawCenteredNoShadow(g: GuiGraphics, font: Font, text: String, centerX: Int, y: Int, color: Int) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false)
    }

    fun drawCenteredNoShadow(g: GuiGraphics, font: Font, text: Component, centerX: Int, y: Int, color: Int) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false)
    }

    fun drawCenteredNoShadow(g: GuiGraphics, font: Font, text: FormattedCharSequence, centerX: Int, y: Int, color: Int) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false)
    }

    private fun drawNineSlice(
        g: GuiGraphics,
        tex: ResourceLocation,
        texW: Int,
        texH: Int,
        dx: Int,
        dy: Int,
        dw: Int,
        dh: Int,
        uv: UvRect,
        ns: NineSlice,
    ) {
        val l = minOf(ns.left, dw / 2, uv.w / 2).coerceAtLeast(0)
        val r = minOf(ns.right, dw - l, uv.w - l).coerceAtLeast(0)
        val t = minOf(ns.top, dh / 2, uv.h / 2).coerceAtLeast(0)
        val b = minOf(ns.bottom, dh - t, uv.h - t).coerceAtLeast(0)

        val dMidW = (dw - l - r).coerceAtLeast(0)
        val dMidH = (dh - t - b).coerceAtLeast(0)
        val sMidW = (uv.w - l - r).coerceAtLeast(1)
        val sMidH = (uv.h - t - b).coerceAtLeast(1)

        val u0 = uv.u
        val u1 = uv.u + l
        val u2 = uv.u + uv.w - r
        val v0 = uv.v
        val v1 = uv.v + t
        val v2 = uv.v + uv.h - b

        val x0 = dx
        val x1 = dx + l
        val x2 = dx + l + dMidW
        val y0 = dy
        val y1 = dy + t
        val y2 = dy + t + dMidH

        fun blitPart(px: Int, py: Int, pw: Int, ph: Int, su: Int, sv: Int, sw: Int, sh: Int) {
            if (pw <= 0 || ph <= 0 || sw <= 0 || sh <= 0) return
            g.blit(tex, px, py, pw, ph, su.toFloat(), sv.toFloat(), sw, sh, texW, texH)
        }

        blitPart(x0, y0, l, t, u0, v0, l, t)
        blitPart(x2, y0, r, t, u2, v0, r, t)
        blitPart(x0, y2, l, b, u0, v2, l, b)
        blitPart(x2, y2, r, b, u2, v2, r, b)
        blitPart(x1, y0, dMidW, t, u1, v0, sMidW, t)
        blitPart(x1, y2, dMidW, b, u1, v2, sMidW, b)
        blitPart(x0, y1, l, dMidH, u0, v1, l, sMidH)
        blitPart(x2, y1, r, dMidH, u2, v1, r, sMidH)
        blitPart(x1, y1, dMidW, dMidH, u1, v1, sMidW, sMidH)
    }

    fun drawMissingCheckerboard(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        val cell = 6
        var cy = y
        var row = 0
        while (cy < y + h) {
            val ch = minOf(cell, y + h - cy)
            var cx = x
            var col = 0
            while (cx < x + w) {
                val cw = minOf(cell, x + w - cx)
                val color = if ((row + col) % 2 == 0) 0xFF_FF00FF.toInt() else 0xFF_000000.toInt()
                g.fill(cx, cy, cx + cw, cy + ch, color)
                cx += cw
                col++
            }
            cy += ch
            row++
        }
    }
}
