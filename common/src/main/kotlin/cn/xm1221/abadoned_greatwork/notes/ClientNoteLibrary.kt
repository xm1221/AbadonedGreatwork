package cn.xm1221.abadoned_greatwork.notes

import net.minecraft.network.chat.Component

/**
 * 客户端笔记缓存与状态通知中心。
 */
object ClientNoteLibrary {

    var entries: List<NoteEntry> = emptyList()
        private set

    var excludedActions: Set<String> = emptySet()
        private set

    var dirtyForBook: Boolean = false

    var lastResultSuccess: Boolean = true
        private set

    var lastResultMessage: Component? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** 由客户端初始化时注册：打开笔记库 / 残页预览（隔离 Screen 引用以保证专用服务器安全） */
    var openLibraryScreenHook: (() -> Unit)? = null
    var openScrapPreviewHook: ((NoteEntry, Boolean) -> Unit)? = null

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun applySync(newEntries: List<NoteEntry>, newExcludedActions: Collection<String>) {
        entries = newEntries
        excludedActions = newExcludedActions.toSet()
        dirtyForBook = true
        notifyListeners()
    }

    fun applyResult(success: Boolean, messageKey: String, args: List<String>) {
        lastResultSuccess = success
        lastResultMessage = Component.translatable(messageKey, *args.toTypedArray())
        notifyListeners()
    }

    fun clearResult() {
        lastResultMessage = null
    }

    fun clearOnDisconnect() {
        entries = emptyList()
        excludedActions = emptySet()
        lastResultMessage = null
        dirtyForBook = true
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
