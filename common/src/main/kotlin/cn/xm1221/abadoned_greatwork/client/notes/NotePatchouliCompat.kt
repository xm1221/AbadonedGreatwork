package cn.xm1221.abadoned_greatwork.client.notes

import cn.xm1221.abadoned_greatwork.client.notes.patchouli.UserNoteBookInjector
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import dev.architectury.platform.Platform
import net.minecraft.client.gui.screens.Screen

/**
 * 笔记系统对 Patchouli 的可选兼容隔离层：
 * 所有 `vazkii.patchouli.*` 类引用全部局限在 `client/notes/patchouli/` 内。
 */
object NotePatchouliCompat {

    fun isAvailable(): Boolean = Platform.isModLoaded("patchouli")

    fun init() {
        if (isAvailable()) {
            UserNoteBookInjector.init()
        }
    }

    fun openEntryInBook(entryId: String, page: Int = 0) {
        if (isAvailable()) {
            UserNoteBookInjector.openEntryInBook(entryId, page)
        }
    }

    fun openDraftPreview(entry: NoteEntry, returnScreen: Screen, pageIndex: Int = 0) {
        if (isAvailable()) {
            UserNoteBookInjector.openDraftPreview(entry, returnScreen, pageIndex)
        }
    }
}
