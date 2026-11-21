package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 客户端请求调整一条笔记在列表中的排序位置。
 */
data class MsgNoteReorderC2S(
    val entryId: String,
    val delta: Int,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteReorderC2S> {
        override val type = MsgNoteReorderC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteReorderC2S = MsgNoteReorderC2S(
            entryId = buf.readUtf(64),
            delta = buf.readVarInt(),
        )

        override fun MsgNoteReorderC2S.encode(buf: FriendlyByteBuf) {
            buf.writeUtf(entryId, 64)
            buf.writeVarInt(delta)
        }
    }
}
