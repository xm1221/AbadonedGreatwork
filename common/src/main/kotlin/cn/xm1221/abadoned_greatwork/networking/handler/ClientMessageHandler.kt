package cn.xm1221.abadoned_greatwork.networking.handler

import dev.architectury.networking.NetworkManager.PacketContext
import cn.xm1221.abadoned_greatwork.client.ponder.PonderClientState
import cn.xm1221.abadoned_greatwork.client.renderer.AmbitBoundaryRenderer
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import cn.xm1221.abadoned_greatwork.networking.msg.*
import cn.xm1221.abadoned_greatwork.notes.ClientNoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteEntry

fun Abadoned_greatworkMessageS2C.applyOnClient(ctx: PacketContext) = ctx.queue {
    when (this) {
        is MsgSyncConfigS2C -> {
            Abadoned_greatworkServerConfig.onSyncConfig(serverConfig)
        }

        is MsgSyncAmbitLinesS2C -> {
            AmbitBoundaryRenderer.updateLoops(dimension, loops)
        }

        is MsgPonderIndexSyncS2C -> {
            PonderClientState.onIndexChunk(sessionId, chunkIndex, lastChunk, data)
        }

        is MsgPonderLoadS2C -> {
            PonderClientState.onBundleChunk(sessionId, chunkIndex, lastChunk, data)
        }

        is MsgPonderOpenIndexS2C -> {
            PonderClientState.onOpenIndexPacket()
        }

        is MsgPonderSandboxExecS2C -> {
            PonderClientState.onSandboxResult(this)
        }

        is MsgNoteLibrarySyncS2C -> {
            val parsed = entries.mapNotNull { NoteEntry.fromTag(it) }
            ClientNoteLibrary.applySync(parsed, excludedActions)
        }

        is MsgNoteResultS2C -> {
            ClientNoteLibrary.applyResult(success, messageKey, args)
        }
    }
}
