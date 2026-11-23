package cn.xm1221.abadoned_greatwork.client.notes

import cn.xm1221.abadoned_greatwork.client.notes.screen.NoteLibraryScreen
import cn.xm1221.abadoned_greatwork.item.ItemAmethystPen
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import dev.architectury.event.events.client.ClientCommandRegistrationEvent
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * 客户端本地笔记指令：
 * - `/abwnotes open`：打开笔记库（背包需有紫水晶笔或创造模式）
 * - `/abwnotes export <索引>`：导出指定条目到 `<gameDir>/abadoned_greatwork/notes/`
 * - `/abwnotes import <文件名>`：从本地 JSON 导入一条笔记并发送到服务端保存
 */
object NoteClientCommands {

    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        ClientCommandRegistrationEvent.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandRegistrationEvent.literal("abwnotes")
                    .then(
                        ClientCommandRegistrationEvent.literal("open").executes { ctx ->
                            val player = ctx.source.`arch$getPlayer`()
                            if (!ItemAmethystPen.hasPen(player)) {
                                ctx.source.`arch$sendFailure`(
                                    Component.translatable("abadoned_greatwork.notes.error.need_pen")
                                )
                                return@executes 0
                            }
                            val mc = Minecraft.getInstance()
                            mc.execute {
                                mc.setScreen(NoteLibraryScreen())
                            }
                            1
                        }
                    )
                    .then(
                        ClientCommandRegistrationEvent.literal("export").then(
                            ClientCommandRegistrationEvent.argument("index", IntegerArgumentType.integer(0))
                                .suggests { _, builder ->
                                    val indices = ClientNoteLibrary.entries.indices.map { it.toString() }
                                    SharedSuggestionProvider.suggest(indices, builder)
                                }
                                .executes { ctx ->
                                    val idx = IntegerArgumentType.getInteger(ctx, "index")
                                    val entry = ClientNoteLibrary.entries.getOrNull(idx)
                                    if (entry == null) {
                                        ctx.source.`arch$sendFailure`(
                                            Component.translatable("abadoned_greatwork.notes.error.bad_index", idx)
                                        )
                                        return@executes 0
                                    }
                                    val fileName = "export_${idx}.json"
                                    val res = NoteFileIO.exportEntry(entry, fileName)
                                    res.onSuccess { path ->
                                        ctx.source.`arch$sendSuccess`(
                                            { Component.translatable("abadoned_greatwork.notes.exported", path.fileName.toString()) },
                                            false,
                                        )
                                    }.onFailure { err ->
                                        ctx.source.`arch$sendFailure`(
                                            Component.translatable("abadoned_greatwork.notes.error.io", err.message ?: "error")
                                        )
                                    }
                                    if (res.isSuccess) 1 else 0
                                }
                        )
                    )
                    .then(
                        ClientCommandRegistrationEvent.literal("import").then(
                            ClientCommandRegistrationEvent.argument("file", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(NoteFileIO.listJsonFiles(), builder)
                                }
                                .executes { ctx ->
                                    val file = StringArgumentType.getString(ctx, "file")
                                    val res = NoteFileIO.importAndSendToServer(file)
                                    res.onSuccess { entry ->
                                        val title = entry.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
                                        ctx.source.`arch$sendSuccess`(
                                            { Component.translatable("abadoned_greatwork.notes.imported", title) },
                                            false,
                                        )
                                    }.onFailure { err ->
                                        val msgKey = err.message ?: "abadoned_greatwork.notes.error.io"
                                        val comp = if (msgKey.startsWith("abadoned_greatwork.")) {
                                            Component.translatable(msgKey)
                                        } else {
                                            Component.translatable("abadoned_greatwork.notes.error.io", msgKey)
                                        }
                                        ctx.source.`arch$sendFailure`(comp)
                                    }
                                    if (res.isSuccess) 1 else 0
                                }
                        )
                    )
            )
        }
    }
}
