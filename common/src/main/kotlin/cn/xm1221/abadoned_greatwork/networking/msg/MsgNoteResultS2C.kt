package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 服务端返回笔记操作结果（供编辑器或图书馆屏幕显示状态提示）。
 */
data class MsgNoteResultS2C(
    val success: Boolean,
    val messageKey: String,
    val args: List<String> = emptyList(),
) : Abadoned_greatworkMessageS2C {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteResultS2C> {
        override val type = MsgNoteResultS2C::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteResultS2C {
            val success = buf.readBoolean()
            val key = buf.readUtf(128)
            val count = buf.readVarInt()
            val args = ArrayList<String>(count)
            repeat(count) {
                args.add(buf.readUtf(256))
            }
            return MsgNoteResultS2C(success, key, args)
        }

        override fun MsgNoteResultS2C.encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(success)
            buf.writeUtf(messageKey, 128)
            buf.writeVarInt(args.size)
            for (arg in args) {
                buf.writeUtf(arg, 256)
            }
        }
    }
}
