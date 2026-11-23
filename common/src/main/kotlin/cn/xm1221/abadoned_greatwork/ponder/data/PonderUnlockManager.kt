package cn.xm1221.abadoned_greatwork.ponder.data

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Items
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * 数据驱动的「问天」章节锁定与解锁管理器（服务端 SavedData 持久化）。
 *
 * 支持的条件类型（`unlock.conditions`）：
 * - `advancement`: 完成指定进度 (`id`)
 * - `item`: 背包拥有或曾拾取/合成指定物品 (`id`)
 * - `item_tag`: 背包拥有匹配物品标签的任意物品 (`id`)
 * - `scene`: 已打开/查看过前置章节 (`id`)
 * - `manual`: 仅通过 `/abw tianwen unlock` 指令解锁
 */
object PonderUnlockManager {

    private const val DATA_NAME = "${Abadoned_greatwork.MODID}_ponder_unlocks"

    class PlayerProgress {
        val unlocked: LinkedHashSet<ResourceLocation> = LinkedHashSet()
        val forcedLocked: LinkedHashSet<ResourceLocation> = LinkedHashSet()
        val viewed: LinkedHashSet<ResourceLocation> = LinkedHashSet()
    }

    class PonderUnlockSavedData : SavedData() {
        val players: HashMap<UUID, PlayerProgress> = HashMap()

        fun getOrCreate(uuid: UUID): PlayerProgress =
            players.getOrPut(uuid) { PlayerProgress() }

        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
            val playersTag = CompoundTag()
            for ((uuid, prog) in players) {
                if (prog.unlocked.isEmpty() && prog.forcedLocked.isEmpty() && prog.viewed.isEmpty()) continue
                val pTag = CompoundTag()
                pTag.put("unlocked", writeRlList(prog.unlocked))
                pTag.put("forced_locked", writeRlList(prog.forcedLocked))
                pTag.put("viewed", writeRlList(prog.viewed))
                playersTag.put(uuid.toString(), pTag)
            }
            tag.put("players", playersTag)
            return tag
        }

        companion object {
            val FACTORY: Factory<PonderUnlockSavedData> = Factory(::PonderUnlockSavedData, ::load, null as net.minecraft.util.datafix.DataFixTypes?)

            fun load(tag: CompoundTag, registries: HolderLookup.Provider): PonderUnlockSavedData {
                val data = PonderUnlockSavedData()
                val playersTag = tag.getCompound("players")
                for (key in playersTag.allKeys) {
                    val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                    val pTag = playersTag.getCompound(key)
                    val prog = PlayerProgress()
                    readRlList(pTag.getList("unlocked", Tag.TAG_STRING.toInt()), prog.unlocked)
                    readRlList(pTag.getList("forced_locked", Tag.TAG_STRING.toInt()), prog.forcedLocked)
                    readRlList(pTag.getList("viewed", Tag.TAG_STRING.toInt()), prog.viewed)
                    data.players[uuid] = prog
                }
                return data
            }

            private fun writeRlList(set: Set<ResourceLocation>): ListTag {
                val list = ListTag()
                for (rl in set) list.add(StringTag.valueOf(rl.toString()))
                return list
            }

            private fun readRlList(list: ListTag, out: MutableSet<ResourceLocation>) {
                for (i in 0 until list.size) {
                    ResourceLocation.tryParse(list.getString(i))?.let { out.add(it) }
                }
            }
        }
    }

    private fun getSavedData(server: MinecraftServer): PonderUnlockSavedData {
        val overworld: ServerLevel = server.overworld()
        return overworld.dataStorage.computeIfAbsent(
            PonderUnlockSavedData.FACTORY,
            DATA_NAME,
        )
    }

    fun isUnlocked(player: ServerPlayer, scene: PonderSceneDef): Boolean {
        val data = getSavedData(player.server)
        val prog = data.getOrCreate(player.uuid)
        if (prog.forcedLocked.contains(scene.id)) return false
        if (!scene.unlock.locked) return true
        if (prog.unlocked.contains(scene.id)) return true

        if (evaluateUnlockRule(player, scene.unlock, prog)) {
            prog.unlocked.add(scene.id)
            data.setDirty()
            return true
        }
        return false
    }

    fun evaluateAndSyncIfChanged(player: ServerPlayer, sendToast: Boolean) {
        val scenes = PonderSceneLoader.scenes.values
        if (scenes.none { it.unlock.locked }) return
        val data = getSavedData(player.server)
        val prog = data.getOrCreate(player.uuid)

        val newlyUnlocked = ArrayList<PonderSceneDef>()
        for (scene in scenes) {
            if (!scene.unlock.locked) continue
            if (prog.forcedLocked.contains(scene.id) || prog.unlocked.contains(scene.id)) continue
            if (evaluateUnlockRule(player, scene.unlock, prog)) {
                prog.unlocked.add(scene.id)
                newlyUnlocked.add(scene)
            }
        }

        if (newlyUnlocked.isNotEmpty()) {
            data.setDirty()
            PonderSceneLoader.syncIndexTo(player)
            if (sendToast) {
                for (scene in newlyUnlocked) {
                    player.displayClientMessage(
                        Component.translatable("hexponder.ui.unlocked_toast", Component.translatable(scene.title)),
                        false,
                    )
                }
            }
        }
    }

    fun markViewed(player: ServerPlayer, sceneId: ResourceLocation) {
        val data = getSavedData(player.server)
        val prog = data.getOrCreate(player.uuid)
        if (prog.viewed.add(sceneId)) {
            data.setDirty()
        }
        evaluateAndSyncIfChanged(player, sendToast = true)
    }

    fun unlockScene(player: ServerPlayer, sceneId: ResourceLocation) {
        val data = getSavedData(player.server)
        val prog = data.getOrCreate(player.uuid)
        prog.forcedLocked.remove(sceneId)
        prog.unlocked.add(sceneId)
        data.setDirty()
        PonderSceneLoader.syncIndexTo(player)
    }

    fun unlockAll(player: ServerPlayer) {
        val data = getSavedData(player.server)
        val prog = data.getOrCreate(player.uuid)
        prog.forcedLocked.clear()
        for (id in PonderSceneLoader.scenes.keys) {
            prog.unlocked.add(id)
        }
        data.setDirty()
        PonderSceneLoader.syncIndexTo(player)
    }

    fun lockScene(player: ServerPlayer, sceneId: ResourceLocation) {
        val data = getSavedData(player.server)
        val prog = data.getOrCreate(player.uuid)
        prog.unlocked.remove(sceneId)
        prog.forcedLocked.add(sceneId)
        data.setDirty()
        PonderSceneLoader.syncIndexTo(player)
    }

    fun resetPlayer(player: ServerPlayer) {
        val data = getSavedData(player.server)
        data.players.remove(player.uuid)
        data.setDirty()
        PonderSceneLoader.syncIndexTo(player)
    }

    private fun evaluateUnlockRule(
        player: ServerPlayer,
        rule: PonderUnlockDef,
        prog: PlayerProgress,
    ): Boolean {
        if (!rule.locked) return true
        if (rule.conditions.isEmpty()) return false
        return if (rule.mode.equals("any", ignoreCase = true)) {
            rule.conditions.any { isConditionMet(player, it, prog) }
        } else {
            rule.conditions.all { isConditionMet(player, it, prog) }
        }
    }

    private fun isConditionMet(
        player: ServerPlayer,
        cond: PonderUnlockCondition,
        prog: PlayerProgress,
    ): Boolean {
        val target = ResourceLocation.tryParse(cond.id)
        return when (cond.type.lowercase()) {
            "advancement" -> {
                val rl = target ?: return false
                val adv = player.server.advancements.get(rl) ?: return false
                player.advancements.getOrStartProgress(adv).isDone
            }
            "item" -> {
                val rl = target ?: return false
                val item = BuiltInRegistries.ITEM.get(rl)
                if (item == Items.AIR) return false
                if (player.inventory.contains(item.defaultInstance)) return true
                player.stats.getValue(Stats.ITEM_PICKED_UP.get(item)) > 0 ||
                    player.stats.getValue(Stats.ITEM_CRAFTED.get(item)) > 0
            }
            "item_tag" -> {
                val rl = target ?: return false
                val tagKey = TagKey.create(Registries.ITEM, rl)
                for (i in 0 until player.inventory.containerSize) {
                    val stack = player.inventory.getItem(i)
                    if (!stack.isEmpty && stack.`is`(tagKey)) return true
                }
                false
            }
            "scene" -> {
                val rl = target ?: return false
                prog.viewed.contains(rl)
            }
            "manual" -> false
            else -> false
        }
    }
}
