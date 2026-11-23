package cn.xm1221.abadoned_greatwork.client.notes

import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteUpsertC2S
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.architectury.platform.Platform
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.streams.toList

/**
 * 客户端本地 JSON 导出/导入工具（目录：`<gameDir>/abadoned_greatwork/notes/`）。
 */
object NoteFileIO {

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    val notesDir: Path
        get() = Platform.getGameFolder().resolve("abadoned_greatwork").resolve("notes")

    fun listJsonFiles(): List<String> {
        val dir = notesDir
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json", ignoreCase = true) }
                    .sorted { a, b ->
                        Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a))
                    }
                    .map { it.fileName.toString() }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun exportEntry(entry: NoteEntry, fileNameOverride: String? = null): Result<Path> = runCatching {
        Files.createDirectories(notesDir)
        val rawName = fileNameOverride ?: buildSafeFileName(entry)
        val fileName = if (rawName.endsWith(".json", ignoreCase = true)) rawName else "$rawName.json"
        val target = notesDir.resolve(fileName).normalize()
        require(target.startsWith(notesDir.normalize())) { "Invalid path" }

        val root = JsonObject().apply {
            addProperty("format", "abadoned_greatwork:note")
            addProperty("version", 1)
            add("entry", entry.toJson())
        }
        Files.writeString(target, GSON.toJson(root), StandardCharsets.UTF_8)
        target
    }

    fun importEntry(fileName: String): Result<NoteEntry> = runCatching {
        val cleanName = fileName.trim()
        require(cleanName.isNotEmpty()) { "Empty filename" }
        val resolved = notesDir.resolve(if (cleanName.endsWith(".json", true)) cleanName else "$cleanName.json").normalize()
        require(resolved.startsWith(notesDir.normalize())) { "Invalid filename" }
        require(Files.isRegularFile(resolved)) { "File not found: ${resolved.fileName}" }

        val rawText = Files.readString(resolved, StandardCharsets.UTF_8)
        val parsed = NoteEntry.parseJsonString(rawText) ?: error("Invalid note JSON")
        val withNewId = parsed.copy(id = UUID.randomUUID().toString())
        when (val res = NoteValidator.validateStructure(withNewId)) {
            is NoteValidator.ValidationResult.Ok -> res.entry
            is NoteValidator.ValidationResult.Err -> error(res.messageKey)
        }
    }

    fun importAndSendToServer(fileName: String): Result<NoteEntry> {
        val res = importEntry(fileName)
        res.onSuccess { entry ->
            MsgNoteUpsertC2S(entry.toTag()).sendToServer()
        }
        return res
    }

    private fun buildSafeFileName(entry: NoteEntry): String {
        val base = entry.title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "note" }
        val shortId = entry.id.take(6).ifBlank { "entry" }
        return "${base}_${shortId}.json"
    }
}
