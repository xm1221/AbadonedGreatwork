package cn.xm1221.abadoned_greatwork.client.notes.patchouli

import at.petrak.hexcasting.api.HexAPI
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.mixin.BookContentsBuilderAccessor
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import com.google.gson.JsonObject
import dev.architectury.event.CompoundEventResult
import dev.architectury.event.events.client.ClientGuiEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import vazkii.patchouli.client.book.BookCategory
import vazkii.patchouli.client.book.BookContentsBuilder
import vazkii.patchouli.client.book.BookEntry
import vazkii.patchouli.client.book.BookPage
import vazkii.patchouli.client.book.ClientBookRegistry
import vazkii.patchouli.client.book.gui.GuiBook
import vazkii.patchouli.client.book.gui.GuiBookEntry
import vazkii.patchouli.client.book.page.PageText
import vazkii.patchouli.client.book.template.BookTemplate
import vazkii.patchouli.common.book.Book
import vazkii.patchouli.common.book.BookRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Patchouli 动态条目注入与容错构建器。
 * 严格遵守可选依赖隔离契约：所有 `vazkii.patchouli.*` 类引用均局限在 `client/notes/patchouli/` 包内。
 */
object UserNoteBookInjector {

    private val HEXBOOK_ID: ResourceLocation = HexAPI.modLoc("thehexbook")
    private val PLAYER_NOTES_CATEGORY_ID: ResourceLocation = ResourceLocation("hexcasting", "abadoned_greatwork/player_notes")
    private val DRAFT_PREVIEW_ID: ResourceLocation = Abadoned_greatwork.id("${NoteEntry.ENTRY_PATH_PREFIX}__draft_preview__")

    private val cachedTemplates: MutableMap<ResourceLocation, Supplier<BookTemplate>> = ConcurrentHashMap()
    private var initialized = false
    private var reloadingNow = false

    fun init() {
        if (initialized) return
        initialized = true
        ClientBookRegistry.INSTANCE.pageTypes[Abadoned_greatwork.id("iota_display")] = PageIotaDisplay::class.java

        ClientGuiEvent.SET_SCREEN.register { screen ->
            if (!reloadingNow && screen is GuiBook && screen !is PreviewBookEntryScreen) {
                if (screen.book.id == HEXBOOK_ID && ClientNoteLibrary.dirtyForBook) {
                    refreshBookIfNeeded()
                }
            }
            CompoundEventResult.pass()
        }
    }

    @JvmStatic
    fun isUserNote(entryId: ResourceLocation?): Boolean {
        if (entryId == null) return false
        return entryId.namespace == Abadoned_greatwork.MODID && entryId.path.startsWith(NoteEntry.ENTRY_PATH_PREFIX)
    }

    @JvmStatic
    fun inject(
        book: Book,
        categories: MutableMap<ResourceLocation, BookCategory>,
        entries: MutableMap<ResourceLocation, BookEntry>,
        templates: Map<ResourceLocation, Supplier<BookTemplate>>,
    ) {
        if (book.id != HEXBOOK_ID) return
        try {
            cachedTemplates.clear()
            cachedTemplates.putAll(templates)

            // 兜底确保分类存在
            if (!categories.containsKey(PLAYER_NOTES_CATEGORY_ID)) {
                val catJson = JsonObject().apply {
                    addProperty("name", "abadoned_greatwork.category.player_notes")
                    addProperty("description", "abadoned_greatwork.category.player_notes.desc")
                    addProperty("icon", "abadoned_greatwork:amethyst_pen")
                    addProperty("sortnum", 99)
                }
                val cat = BookCategory(catJson, PLAYER_NOTES_CATEGORY_ID, book)
                if (cat.canAdd()) {
                    categories[PLAYER_NOTES_CATEGORY_ID] = cat
                }
            }

            for ((idx, raw) in ClientNoteLibrary.entries.withIndex()) {
                try {
                    val sanitized = when (val res = NoteValidator.validateStructure(raw)) {
                        is NoteValidator.ValidationResult.Ok -> res.entry
                        is NoteValidator.ValidationResult.Err -> raw
                    }
                    val entryId = Abadoned_greatwork.id(NoteEntry.entryResourcePath(sanitized.id))
                    val json = sanitized.toPatchouliEntryJson(idx)
                    val entry = BookEntry(json, entryId, book, sanitized.author.ifBlank { null })
                    if (entry.canAdd()) {
                        entry.initCategory(entryId) { categories[it] }
                        entries[entryId] = entry
                    }
                } catch (e: Exception) {
                    Abadoned_greatwork.LOGGER.warn("Failed to inject user note entry {}: {}", raw.id, e.message)
                }
            }
        } catch (e: Exception) {
            Abadoned_greatwork.LOGGER.error("Unexpected error while injecting user notes into thehexbook", e)
        }
    }

    @JvmStatic
    fun buildSafely(
        level: Level?,
        builder: BookContentsBuilder,
        entry: BookEntry,
        entryId: ResourceLocation,
        book: Book,
        pages: Array<BookPage>,
        realPages: MutableList<BookPage>,
    ) {
        realPages.clear()
        for (i in pages.indices) {
            val page = pages[i]
            if (!page.canAdd(book)) continue
            try {
                page.build(level, entry, builder, i)
                realPages.add(page)
            } catch (e: Exception) {
                Abadoned_greatwork.LOGGER.warn("User note {} page {} failed to build: {}", entryId, i + 1, e.message)
                val fallback = buildFallbackPage(level, builder, entry, i, e.message ?: e.javaClass.simpleName)
                if (fallback != null) {
                    realPages.add(fallback)
                }
            }
        }
    }

    private fun buildFallbackPage(
        level: Level?,
        builder: BookContentsBuilder,
        entry: BookEntry,
        pageIndex: Int,
        reason: String,
    ): BookPage? = runCatching {
        val msg = Component.translatable("abadoned_greatwork.notes.page_build_error", pageIndex + 1, reason).string
            .replace("%", "%%")
        val json = JsonObject().apply {
            addProperty("type", "patchouli:text")
            addProperty("title", Component.translatable("abadoned_greatwork.notes.page_build_error.title").string.replace("%", "%%"))
            addProperty("text", msg)
        }
        val page = ClientBookRegistry.INSTANCE.gson.fromJson(json, PageText::class.java)
        page.sourceObject = json
        page.build(level, entry, builder, pageIndex)
        page
    }.getOrNull()

    fun refreshBookIfNeeded() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val book = BookRegistry.INSTANCE.books[HEXBOOK_ID] ?: return
        if (!ClientNoteLibrary.dirtyForBook) return

        reloadingNow = true
        try {
            ClientBookRegistry.INSTANCE.currentLang = mc.languageManager.selected
            book.reloadContents(level, true)
            book.reloadLocks(true)
            ClientNoteLibrary.dirtyForBook = false
        } finally {
            reloadingNow = false
        }
    }

    fun openEntryInBook(entryId: String, page: Int = 0) {
        val mc = Minecraft.getInstance()
        val book = BookRegistry.INSTANCE.books[HEXBOOK_ID] ?: return
        refreshBookIfNeeded()
        if (book.contents.currentGui is PreviewBookEntryScreen) {
            book.contents.currentGui = null
        }
        book.contents.guiStack.removeIf { it is PreviewBookEntryScreen }

        val targetId = Abadoned_greatwork.id(NoteEntry.entryResourcePath(entryId))
        if (book.contents.entries.containsKey(targetId)) {
            ClientBookRegistry.INSTANCE.displayBookGui(HEXBOOK_ID, targetId, page)
        } else {
            // 若刷新后仍未找到，回退打开分类页
            ClientBookRegistry.INSTANCE.displayBookGui(HEXBOOK_ID, null, 0)
        }
    }

    fun openDraftPreview(entry: NoteEntry, returnScreen: Screen, pageIndex: Int = 0) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val book = BookRegistry.INSTANCE.books[HEXBOOK_ID] ?: return
        if (cachedTemplates.isEmpty()) {
            ClientNoteLibrary.dirtyForBook = true
            refreshBookIfNeeded()
        }

        val sanitized = when (val res = NoteValidator.validateStructure(entry)) {
            is NoteValidator.ValidationResult.Ok -> res.entry
            is NoteValidator.ValidationResult.Err -> entry
        }
        val builder = BookContentsBuilderAccessor.`abw$create`(book, true)
        (builder as BookContentsBuilderAccessor).`abw$getTemplates`().putAll(cachedTemplates)

        val draft = BookEntry(
            sanitized.toPatchouliEntryJson(0),
            DRAFT_PREVIEW_ID,
            book,
            sanitized.author.ifBlank { null },
        )
        draft.build(level, builder)
        val spread = (pageIndex.coerceAtLeast(0)) / 2
        val prevGui = book.contents.currentGui?.takeUnless { it is PreviewBookEntryScreen }
        mc.setScreen(PreviewBookEntryScreen(book, draft, spread, returnScreen, prevGui))
    }

    /**
     * 预览专用 `GuiBookEntry` 子类：关闭时返回编辑器/预览界面，绝不覆盖 `book.contents.currentGui` / `guiStack` 或污染已读历史记录。
     */
    private class PreviewBookEntryScreen(
        book: Book,
        entry: BookEntry,
        spread: Int,
        private val returnScreen: Screen,
        private val previousCurrentGui: GuiBook?,
    ) : GuiBookEntry(book, entry, spread) {

        private fun restoreBookState() {
            if (book.contents.currentGui === this || book.contents.currentGui is PreviewBookEntryScreen) {
                book.contents.currentGui = previousCurrentGui
            }
            book.contents.guiStack.removeIf { it === this || it is PreviewBookEntryScreen }
        }

        override fun init() {
            super.init()
            restoreBookState()
        }

        override fun shouldAddAddBookmarkButton(): Boolean = false

        override fun canSeeBackButton(): Boolean = false

        override fun onFirstOpened() {
            // 草稿预览不写入 PersistentData 历史与已读状态
        }

        override fun removed() {
            restoreBookState()
            super.removed()
        }

        override fun onClose() {
            restoreBookState()
            Minecraft.getInstance().setScreen(returnScreen)
        }
    }
}
