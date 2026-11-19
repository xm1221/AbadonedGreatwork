package cn.xm1221.abadoned_greatwork.networking.msg

import cn.xm1221.abadoned_greatwork.ponder.data.PonderChunkHelper
import cn.xm1221.abadoned_greatwork.ponder.data.PonderPlaybackBundle
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer

/**
 * 服务端下发预模拟完成的思索场景帧序列（按字节分块发送）。
 */
data class MsgPonderLoadS2C(
    val sessionId: Int,
    val chunkIndex: Int,
    val lastChunk: Boolean,
    val data: ByteArray,
) : Abadoned_greatworkMessageS2C {

    companion object : Abadoned_greatworkMessageCompanion<MsgPonderLoadS2C> {
        override val type = MsgPonderLoadS2C::class.java

        fun sendChunked(player: ServerPlayer, bundle: PonderPlaybackBundle) {
            PonderChunkHelper.sendChunks(bundle.toByteArray()) { sid, idx, last, slice ->
                MsgPonderLoadS2C(sid, idx, last, slice).sendToPlayer(player)
            }
        }

        override fun decode(buf: FriendlyByteBuf): MsgPonderLoadS2C = MsgPonderLoadS2C(
            sessionId = buf.readInt(),
            chunkIndex = buf.readInt(),
            lastChunk = buf.readBoolean(),
            data = buf.readByteArray(),
        )

        override fun MsgPonderLoadS2C.encode(buf: FriendlyByteBuf) {
            buf.writeInt(sessionId)
            buf.writeInt(chunkIndex)
            buf.writeBoolean(lastChunk)
            buf.writeByteArray(data)
        }
    }
}
