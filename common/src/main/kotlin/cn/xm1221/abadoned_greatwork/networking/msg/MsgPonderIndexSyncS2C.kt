package cn.xm1221.abadoned_greatwork.networking.msg

import cn.xm1221.abadoned_greatwork.ponder.data.PonderChunkHelper
import cn.xm1221.abadoned_greatwork.ponder.data.PonderIndexPayload
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer

/**
 * 同步思索目录与触发器索引给客户端（按字节分块发送）。
 */
data class MsgPonderIndexSyncS2C(
    val sessionId: Int,
    val chunkIndex: Int,
    val lastChunk: Boolean,
    val data: ByteArray,
) : Abadoned_greatworkMessageS2C {

    companion object : Abadoned_greatworkMessageCompanion<MsgPonderIndexSyncS2C> {
        override val type = MsgPonderIndexSyncS2C::class.java

        fun sendChunked(player: ServerPlayer, payload: PonderIndexPayload) {
            PonderChunkHelper.sendChunks(payload.toByteArray()) { sid, idx, last, slice ->
                MsgPonderIndexSyncS2C(sid, idx, last, slice).sendToPlayer(player)
            }
        }

        override fun decode(buf: FriendlyByteBuf): MsgPonderIndexSyncS2C = MsgPonderIndexSyncS2C(
            sessionId = buf.readInt(),
            chunkIndex = buf.readInt(),
            lastChunk = buf.readBoolean(),
            data = buf.readByteArray(),
        )

        override fun MsgPonderIndexSyncS2C.encode(buf: FriendlyByteBuf) {
            buf.writeInt(sessionId)
            buf.writeInt(chunkIndex)
            buf.writeBoolean(lastChunk)
            buf.writeByteArray(data)
        }
    }
}
