package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf

/**
 * 服务端通知客户端打开独立咒法思索目录。
 */
class MsgPonderOpenIndexS2C : Abadoned_greatworkMessageS2C {

    companion object : Abadoned_greatworkMessageCompanion<MsgPonderOpenIndexS2C> {
        override val type = MsgPonderOpenIndexS2C::class.java

        override fun decode(buf: FriendlyByteBuf): MsgPonderOpenIndexS2C = MsgPonderOpenIndexS2C()

        override fun MsgPonderOpenIndexS2C.encode(buf: FriendlyByteBuf) {}
    }
}
