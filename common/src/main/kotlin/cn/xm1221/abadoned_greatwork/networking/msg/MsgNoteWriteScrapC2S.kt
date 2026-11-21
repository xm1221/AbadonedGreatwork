package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 客户端请求将指定笔记写入为一张笔记残页（消耗纸）。
 */
data class MsgNoteWriteScrapC2S(
    val entryId: String,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteWriteScrapC2S> {
        override val type = MsgNoteWriteScrapC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteWriteScrapC2S = MsgNoteWriteScrapC2S(
            entryId = buf.readUtf(64),
        )

        override fun MsgNoteWriteScrapC2S.encode(buf: FriendlyByteBuf) {
            buf.writeUtf(entryId, 64)
        }
    }
}
