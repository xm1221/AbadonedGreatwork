package cn.xm1221.abadoned_greatwork.client.gui.skin

import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GuiSkinTest {

    @Test
    fun `parseHexColor handles 6-digit 8-digit and invalid hex values`() {
        assertEquals(0xFF_9B59B6.toInt(), GuiSkin.parseHexColor("9B59B6"))
        assertEquals(0xFF_9B59B6.toInt(), GuiSkin.parseHexColor("#9B59B6"))
        assertEquals(0x88_123456.toInt(), GuiSkin.parseHexColor("88123456"))
        assertNull(GuiSkin.parseHexColor(""))
        assertNull(GuiSkin.parseHexColor("XYZ123"))
        assertNull(GuiSkin.parseHexColor("12345"))
    }

    @Test
    fun `fromJson inheritance and merge preserve base sprites and override child regions`() {
        val parentJson = JsonParser.parseString(
            """
            {
              "texture": "abadoned_greatwork:textures/gui/notes/atlas.png",
              "tex_size": [512, 256],
              "sprites": {
                "panel": { "uv": [0, 0, 64, 64], "nine_slice": [10, 10, 10, 10] },
                "button": {
                  "uv": [0, 128, 48, 16],
                  "states": { "normal": [0, 128, 48, 16], "hover": [0, 144, 48, 16] },
                  "nine_slice": [4, 4, 4, 4]
                }
              },
              "colors": {
                "title": "FFF2EBF9",
                "paper_text": "FF2B1F16"
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val childJson = JsonParser.parseString(
            """
            {
              "extends": "parent",
              "layout": {
                "size": [396, 230],
                "regions": {
                  "left_page": [10, 30, 184, 154]
                }
              },
              "colors": {
                "title": "FFAA88FF"
              }
            }
            """.trimIndent(),
        ).asJsonObject

        val parentSkin = GuiSkin.fromJson("parent", parentJson)
        val childSkin = GuiSkin.fromJson("child", childJson, parentSkin)

        assertEquals(ResourceLocation.fromNamespaceAndPath("abadoned_greatwork", "textures/gui/notes/atlas.png"), childSkin.texture)
        assertEquals(512, childSkin.texWidth)
        assertEquals(256, childSkin.texHeight)
        assertEquals(396, childSkin.layoutWidth)
        assertEquals(230, childSkin.layoutHeight)
        assertEquals(SkinRect(10, 30, 184, 154), childSkin.region("left_page"))
        assertEquals(UvRect(0, 144, 48, 16), childSkin.sprites["button"]?.resolve("hover"))
        assertEquals(0xFF_AA88FF.toInt(), childSkin.color("title", 0))
        assertEquals(0xFF_2B1F16.toInt(), childSkin.color("paper_text", 0))

        val overlay = GuiSkin.emptyFallback("overlay").copy(
            regions = mapOf("left_page" to SkinRect(12, 32, 180, 150)),
            colors = mapOf("ok" to 0xFF_78E092.toInt()),
        )
        val merged = GuiSkin.merge(childSkin, overlay)
        assertEquals(SkinRect(12, 32, 180, 150), merged.region("left_page"))
        assertEquals(0xFF_78E092.toInt(), merged.color("ok", 0))
        assertEquals(0xFF_2B1F16.toInt(), merged.color("paper_text", 0))
    }
}
