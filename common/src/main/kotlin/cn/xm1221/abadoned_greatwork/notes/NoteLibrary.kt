package cn.xm1221.abadoned_greatwork.notes

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * 玩家个人笔记库（服务端主库，SavedData 存于主世界 dataStorage）。
 * 结构：`UUID -> MutableList<NoteEntry>`，增删改以条目 `id` 为主键。
 */
class NoteLibrary : SavedData() {

    private val entriesByPlayer: MutableMap<UUID, MutableList<NoteEntry>> = mutableMapOf()

    fun entries(uuid: UUID): List<NoteEntry> =
        entriesByPlayer[uuid]?.toList() ?: emptyList()

    fun getById(uuid: UUID, id: String): NoteEntry? =
        entriesByPlayer[uuid]?.firstOrNull { it.id == id }

    fun getByIndex(uuid: UUID, index: Int): NoteEntry? =
        entriesByPlayer[uuid]?.getOrNull(index)

    /**
     * 插入或更新指定玩家的条目。
     * 若 `id` 已存在则原地更新；否则追加到末尾（受限于 [maxEntries]）。
     */
    fun upsert(uuid: UUID, entry: NoteEntry, maxEntries: Int): Boolean {
        val list = entriesByPlayer.getOrPut(uuid) { mutableListOf() }
        val existingIdx = list.indexOfFirst { it.id == entry.id }
        if (existingIdx >= 0) {
            list[existingIdx] = entry
            setDirty()
            return true
        }
        if (list.size >= maxEntries) {
            return false
        }
        list.add(entry)
        setDirty()
        return true
    }

    fun deleteById(uuid: UUID, id: String): Boolean {
        val list = entriesByPlayer[uuid] ?: return false
        val removed = list.removeIf { it.id == id }
        if (removed) setDirty()
        return removed
    }

    fun deleteByIndex(uuid: UUID, index: Int): Boolean {
        val list = entriesByPlayer[uuid] ?: return false
        if (index !in list.indices) return false
        list.removeAt(index)
        setDirty()
        return true
    }

    fun reorder(uuid: UUID, id: String, delta: Int): Boolean {
        if (delta == 0) return false
        val list = entriesByPlayer[uuid] ?: return false
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val target = (idx + delta).coerceIn(0, list.lastIndex)
        if (target == idx) return false
        val item = list.removeAt(idx)
        list.add(target, item)
        setDirty()
        return true
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val playersTag = ListTag()
        for ((uuid, list) in entriesByPlayer) {
            val pTag = CompoundTag()
            pTag.putUUID("uuid", uuid)
            val entriesTag = ListTag()
            for (entry in list) {
                entriesTag.add(entry.toTag())
            }
            pTag.put("entries", entriesTag)
            playersTag.add(pTag)
        }
        tag.put("players", playersTag)
        return tag
    }

    private fun readFrom(tag: CompoundTag) {
        entriesByPlayer.clear()
        val playersTag = tag.getList("players", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until playersTag.size) {
            val pTag = playersTag.getCompound(i)
            if (!pTag.hasUUID("uuid")) continue
            val uuid = pTag.getUUID("uuid")
            val list = mutableListOf<NoteEntry>()
            val entriesTag = pTag.getList("entries", Tag.TAG_COMPOUND.toInt())
            for (j in 0 until entriesTag.size) {
                NoteEntry.fromTag(entriesTag.getCompound(j))?.let { list.add(it) }
            }
            entriesByPlayer[uuid] = list
        }
    }

    companion object {
        const val NAME = "abadoned_greatwork_notes"

        private val FACTORY: Factory<NoteLibrary> = Factory(::create, ::load, null as net.minecraft.util.datafix.DataFixTypes?)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): NoteLibrary =
            NoteLibrary().also { it.readFrom(tag) }

        private fun create(): NoteLibrary = NoteLibrary()

        fun get(server: MinecraftServer): NoteLibrary =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)

        fun get(level: ServerLevel): NoteLibrary = get(level.server)
    }
}
