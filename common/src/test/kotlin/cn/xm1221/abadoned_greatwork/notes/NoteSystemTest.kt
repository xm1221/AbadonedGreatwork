package cn.xm1221.abadoned_greatwork.notes

import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteBookTextPreview
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteSystemTest {

    @Test
    fun `sanitizeText strips command macros and preserves formatting macros`() {
        val raw = "Hello \$(c:/op @s)world \$(CMD:kill @a)\$(br)\$(l)bold\$()!"
        val cleaned = NoteValidator.sanitizeText(raw)
        assertFalse(cleaned.contains("\$(c:", ignoreCase = true))
        assertFalse(cleaned.contains("\$(cmd:", ignoreCase = true))
        assertTrue(cleaned.contains("\$(br)\$(l)bold\$()!"))
        assertEquals("Hello world \$(br)\$(l)bold\$()!", cleaned)
    }

    @Test
    fun `validateStructure strips entity NBT and rejects unsafe links`() {
        val entityEntry = NoteEntry(
            id = "e1",
            title = "100% Safe",
            author = "xm1221",
            time = 123456L,
            pages = listOf(
                NotePage(
                    type = NotePageType.ENTITY,
                    fields = mapOf("entity" to "minecraft:allay{CustomName:'\"hack\"'}"),
                )
            ),
        )
        val res = NoteValidator.validateStructure(entityEntry, NoteValidator.Limits())
        assertTrue(res is NoteValidator.ValidationResult.Ok)
        val clean = (res as NoteValidator.ValidationResult.Ok).entry
        assertEquals("minecraft:allay", clean.pages[0].field("entity"))

        val patchouliJson = clean.toPatchouliEntryJson(0)
        assertEquals("100%% Safe", patchouliJson.get("name").asString)

        val badLink = entityEntry.copy(
            pages = listOf(
                NotePage(
                    type = NotePageType.LINK,
                    fields = mapOf("url" to "file:///etc/passwd", "link_text" to "Bad"),
                )
            ),
        )
        val badRes = NoteValidator.validateStructure(badLink, NoteValidator.Limits())
        assertTrue(badRes is NoteValidator.ValidationResult.Err)
        assertEquals(0, (badRes as NoteValidator.ValidationResult.Err).pageIndex)

        val secondPageErr = entityEntry.copy(
            pages = listOf(
                NotePage(type = NotePageType.TEXT, text = "Valid first page"),
                NotePage(type = NotePageType.HEX_MANUAL_PATTERN, patterns = listOf(ManualPatternDef("EAST", "xyz_bad"))),
            )
        )
        val secondRes = NoteValidator.validateStructure(secondPageErr, NoteValidator.Limits())
        assertTrue(secondRes is NoteValidator.ValidationResult.Err)
        assertEquals(1, (secondRes as NoteValidator.ValidationResult.Err).pageIndex)
    }

    @Test
    fun `NoteEntry round-trips cleanly through NBT and JSON`() {
        val sampleIota = CompoundTag().apply {
            putString("hexcasting:type", "hexcasting:double")
            putDouble("hexcasting:data", 42.0)
        }
        val original = NoteEntry(
            id = "note-uuid-1",
            title = "Test Guide",
            author = "xm1221",
            time = 1700000000000L,
            icon = "abadoned_greatwork:amethyst_pen",
            color = "54398a",
            pages = listOf(
                NotePage(type = NotePageType.TEXT, title = "Intro", text = "Line 1\nLine 2"),
                NotePage(type = NotePageType.SPOTLIGHT, fields = mapOf("item" to "minecraft:amethyst_shard", "link_recipe" to "true"), text = "Shard"),
                NotePage(type = NotePageType.CRAFTING, fields = mapOf("recipe" to "abadoned_greatwork:amethyst_pen")),
                NotePage(type = NotePageType.HEX_PATTERN, fields = mapOf("op_id" to "hexcasting:get_caster", "output" to "entity")),
                NotePage(
                    type = NotePageType.HEX_MANUAL_PATTERN,
                    title = "Manual",
                    patterns = listOf(ManualPatternDef("EAST", "qaq")),
                    fields = mapOf("stroke_order" to "true"),
                ),
                NotePage(type = NotePageType.IMAGE, images = listOf("hexcasting:textures/gui/entries/test.png"), fields = mapOf("border" to "true")),
                NotePage(type = NotePageType.ENTITY, fields = mapOf("entity" to "minecraft:allay", "scale" to "1.0", "rotate" to "true")),
                NotePage(type = NotePageType.LINK, fields = mapOf("url" to "https://example.com", "link_text" to "Docs")),
                NotePage(type = NotePageType.EMPTY, fields = mapOf("draw_filler" to "true")),
                NotePage(type = NotePageType.IOTA_DISPLAY, title = "Stack", iotas = listOf(sampleIota)),
            ),
        )

        val fromNbt = NoteEntry.fromTag(original.toTag())
        assertEquals(original, fromNbt)

        val jsonStr = original.toJson().toString()
        val fromJson = NoteEntry.parseJsonString(jsonStr)
        assertEquals(original, fromJson)
    }

    @Test
    fun `validateStructure enforces page limit`() {
        val tooMany = NoteEntry(
            id = "overflow",
            title = "Overflow",
            author = "xm1221",
            time = 1L,
            pages = List(5) { NotePage(NotePageType.TEXT, text = "p$it") },
        )
        val res = NoteValidator.validateStructure(tooMany, NoteValidator.Limits(maxPagesPerEntry = 3))
        assertTrue(res is NoteValidator.ValidationResult.Err)
        val err = res as NoteValidator.ValidationResult.Err
        assertEquals("abadoned_greatwork.notes.error.too_many_pages", err.messageKey)
        assertEquals(null, err.pageIndex)
    }

    @Test
    fun `NoteBookTextPreview parses Patchouli macros and line breaks cleanly`() {
        val raw = "Hello \$(thing)Hex/$ \$(l)Bold\$()!\$(br)Line 2\$(br2)Para 2"
        val paras = NoteBookTextPreview.parseToParagraphs(raw)
        assertEquals(4, paras.size)
        assertEquals("Hello Hex Bold!", paras[0].string)
        assertEquals("Line 2", paras[1].string)
        assertEquals("", paras[2].string)
        assertEquals("Para 2", paras[3].string)

        val siblings = paras[0].siblings
        val hexPart = siblings.first { it.string == "Hex" }
        val boldPart = siblings.first { it.string == "Bold" }
        val tailPart = siblings.last()
        assertEquals(NoteBookTextPreview.UNIFORM_FONT_RL, hexPart.style.font)
        assertEquals(0x8D6ACC, hexPart.style.color?.value)
        assertTrue(boldPart.style.isBold)
        assertEquals(0x2B1F16, tailPart.style.color?.value)
    }
}
