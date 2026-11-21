package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 客户端请求按 ID 删除一条笔记。
 */
data class MsgNoteDeleteC2S(
    val entryId: String,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteDeleteC2S> {
        override val type = MsgNoteDeleteC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteDeleteC2S = MsgNoteDeleteC2S(
            entryId = buf.readUtf(64),
        )

        override fun MsgNoteDeleteC2S.encode(buf: FriendlyByteBuf) {
            buf.writeUtf(entryId, 64)
        }
    }
}
