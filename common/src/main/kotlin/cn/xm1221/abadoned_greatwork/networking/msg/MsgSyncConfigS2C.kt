package cn.xm1221.abadoned_greatwork.networking.msg

import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import net.minecraft.network.FriendlyByteBuf

data class MsgSyncConfigS2C(val serverConfig: Abadoned_greatworkServerConfig.ServerConfig) : Abadoned_greatworkMessageS2C {
    companion object : Abadoned_greatworkMessageCompanion<MsgSyncConfigS2C> {
        override val type = MsgSyncConfigS2C::class.java

        override fun decode(buf: FriendlyByteBuf) = MsgSyncConfigS2C(
            serverConfig = Abadoned_greatworkServerConfig.ServerConfig().decode(buf),
        )

        override fun MsgSyncConfigS2C.encode(buf: FriendlyByteBuf) {
            serverConfig.encode(buf)
        }
    }
}
