package cn.xm1221.abadoned_greatwork.networking.handler

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import cn.xm1221.abadoned_greatwork.item.ItemAmethystPen
import cn.xm1221.abadoned_greatwork.item.ItemNoteScrap
import cn.xm1221.abadoned_greatwork.networking.msg.Abadoned_greatworkMessageC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteDeleteC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteImportScrapC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteReorderC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteResultS2C
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteUpsertC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgNoteWriteScrapC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderLoadC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderLoadS2C
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecS2C
import cn.xm1221.abadoned_greatwork.notes.NoteEntry
import cn.xm1221.abadoned_greatwork.notes.NoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteSync
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import cn.xm1221.abadoned_greatwork.ponder.data.PonderSceneLoader
import cn.xm1221.abadoned_greatwork.ponder.data.PonderUnlockManager
import cn.xm1221.abadoned_greatwork.ponder.sim.PonderPreSimulator
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import dev.architectury.networking.NetworkManager.PacketContext
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private object NotePacketRateLimiter {
    private val lastTickByPlayer = ConcurrentHashMap<UUID, Long>()
    private const val MIN_INTERVAL_TICKS = 3L

    fun allow(player: ServerPlayer): Boolean {
        val now = player.server.overworld().gameTime
        val prev = lastTickByPlayer[player.uuid]
        if (prev != null && now - prev < MIN_INTERVAL_TICKS) {
            return false
        }
        lastTickByPlayer[player.uuid] = now
        return true
    }
}

fun Abadoned_greatworkMessageC2S.applyOnServer(ctx: PacketContext) = ctx.queue {
    when (this) {
        is MsgPonderLoadC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            val scene = PonderSceneLoader.getScene(sceneId) ?: return@queue
            if (!PonderUnlockManager.isUnlocked(player, scene)) {
                player.displayClientMessage(Component.translatable("hexponder.ui.locked_deny"), true)
                PonderSceneLoader.syncIndexTo(player)
                return@queue
            }
            PonderUnlockManager.markViewed(player, scene.id)
            val bundle = PonderPreSimulator.simulate(scene, player)
            MsgPonderLoadS2C.sendChunked(player, bundle)
        }

        is MsgPonderSandboxExecC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            val pattern = try {
                patternFromAngles(sig, HexDir.valueOf(startDir.uppercase()))
            } catch (_: Exception) {
                return@queue
            }
            val result = PonderPreSimulator.executeSandbox(player, pattern, imageNbt)
            MsgPonderSandboxExecS2C(
                sessionId = sessionId,
                imageNbt = result.imageNbt,
                resolutionType = result.resolutionType,
                consoleLines = result.consoleLines,
                mishapText = result.mishapText,
            ).sendToPlayer(player)
        }

        is MsgNoteUpsertC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (!NotePacketRateLimiter.allow(player)) return@queue
            if (!ItemAmethystPen.hasPen(player)) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.error.need_pen").sendToPlayer(player)
                return@queue
            }
            val raw = NoteEntry.fromTag(entryTag)
            if (raw == null) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.error.invalid_data").sendToPlayer(player)
                return@queue
            }
            val limits = NoteValidator.currentLimits()
            val candidate = raw.copy(
                author = raw.author.ifBlank { player.gameProfile.name },
                time = System.currentTimeMillis(),
            )
            when (val res = NoteValidator.validateServer(candidate, player.serverLevel(), limits)) {
                is NoteValidator.ValidationResult.Err -> {
                    MsgNoteResultS2C(false, res.messageKey, res.args).sendToPlayer(player)
                }
                is NoteValidator.ValidationResult.Ok -> {
                    val lib = NoteLibrary.get(player.server)
                    if (!lib.upsert(player.uuid, res.entry, limits.maxEntriesPerPlayer)) {
                        MsgNoteResultS2C(
                            false,
                            "abadoned_greatwork.notes.error.library_full",
                            listOf(limits.maxEntriesPerPlayer.toString()),
                        ).sendToPlayer(player)
                        return@queue
                    }
                    NoteSync.syncAll(player)
                    MsgNoteResultS2C(true, "abadoned_greatwork.notes.status.saved").sendToPlayer(player)
                }
            }
        }

        is MsgNoteDeleteC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (!NotePacketRateLimiter.allow(player)) return@queue
            if (!ItemAmethystPen.hasPen(player)) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.error.need_pen").sendToPlayer(player)
                return@queue
            }
            val lib = NoteLibrary.get(player.server)
            if (lib.deleteById(player.uuid, entryId)) {
                NoteSync.syncAll(player)
                MsgNoteResultS2C(true, "abadoned_greatwork.notes.status.deleted").sendToPlayer(player)
            }
        }

        is MsgNoteReorderC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (!NotePacketRateLimiter.allow(player)) return@queue
            if (!ItemAmethystPen.hasPen(player)) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.error.need_pen").sendToPlayer(player)
                return@queue
            }
            val lib = NoteLibrary.get(player.server)
            if (lib.reorder(player.uuid, entryId, delta.coerceIn(-1, 1))) {
                NoteSync.syncAll(player)
            }
        }

        is MsgNoteWriteScrapC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (!NotePacketRateLimiter.allow(player)) return@queue
            if (!ItemAmethystPen.hasPen(player)) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.error.need_pen").sendToPlayer(player)
                return@queue
            }
            val lib = NoteLibrary.get(player.server)
            val entry = lib.getById(player.uuid, entryId)
            if (entry == null) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.error.not_found").sendToPlayer(player)
                return@queue
            }
            val cost = (entry.pages.size.coerceAtLeast(1)) * Abadoned_greatworkServerConfig.config.paperPerPage.coerceIn(0, 16)
            if (!NoteSync.consumePaper(player, cost)) {
                MsgNoteResultS2C(
                    false,
                    "abadoned_greatwork.notes.error.need_paper",
                    listOf(cost.toString(), NoteSync.countPaper(player).toString()),
                ).sendToPlayer(player)
                return@queue
            }
            val scrap = ItemStack(Abadoned_greatworkItems.NOTE_SCRAP.value)
            ItemNoteScrap.setEntry(scrap, entry)
            if (!player.addItem(scrap)) {
                player.drop(scrap, false)
            }
            MsgNoteResultS2C(true, "abadoned_greatwork.notes.status.wrote_scrap", listOf(cost.toString())).sendToPlayer(player)
        }

        is MsgNoteImportScrapC2S -> {
            val player = ctx.player as? ServerPlayer ?: return@queue
            if (!NotePacketRateLimiter.allow(player)) return@queue
            val hand = if (offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            val stack = player.getItemInHand(hand)
            if (!stack.`is`(Abadoned_greatworkItems.NOTE_SCRAP.value)) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.scrap_empty").sendToPlayer(player)
                return@queue
            }
            val parsed = ItemNoteScrap.getEntry(stack)
            if (parsed == null) {
                MsgNoteResultS2C(false, "abadoned_greatwork.notes.scrap_empty").sendToPlayer(player)
                return@queue
            }
            val limits = NoteValidator.currentLimits()
            val candidate = parsed.copy(id = UUID.randomUUID().toString())
            when (val res = NoteValidator.validateServer(candidate, player.serverLevel(), limits)) {
                is NoteValidator.ValidationResult.Err -> {
                    MsgNoteResultS2C(false, res.messageKey, res.args).sendToPlayer(player)
                }
                is NoteValidator.ValidationResult.Ok -> {
                    val lib = NoteLibrary.get(player.server)
                    if (!lib.upsert(player.uuid, res.entry, limits.maxEntriesPerPlayer)) {
                        MsgNoteResultS2C(
                            false,
                            "abadoned_greatwork.notes.error.library_full",
                            listOf(limits.maxEntriesPerPlayer.toString()),
                        ).sendToPlayer(player)
                        return@queue
                    }
                    NoteSync.syncAll(player)
                    val title = res.entry.title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
                    MsgNoteResultS2C(true, "abadoned_greatwork.notes.imported", listOf(title)).sendToPlayer(player)
                }
            }
        }
    }
}
