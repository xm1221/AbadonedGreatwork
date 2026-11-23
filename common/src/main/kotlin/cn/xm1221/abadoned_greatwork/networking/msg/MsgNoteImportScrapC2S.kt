package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 客户端从手持笔记残页确认收录到个人笔记库（服务端校验手头物品，不消耗残页）。
 */
data class MsgNoteImportScrapC2S(
    val offhand: Boolean,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteImportScrapC2S> {
        override val type = MsgNoteImportScrapC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteImportScrapC2S = MsgNoteImportScrapC2S(
            offhand = buf.readBoolean(),
        )

        override fun MsgNoteImportScrapC2S.encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(offhand)
        }
    }
}
