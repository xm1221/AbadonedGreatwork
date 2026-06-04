package cn.xm1221.abadoned_greatwork.networking.handler

import dev.architectury.networking.NetworkManager.PacketContext
import cn.xm1221.abadoned_greatwork.config.Abadoned_greatworkServerConfig
import cn.xm1221.abadoned_greatwork.networking.msg.*

fun Abadoned_greatworkMessageS2C.applyOnClient(ctx: PacketContext) = ctx.queue {
    when (this) {
        is MsgSyncConfigS2C -> {
            Abadoned_greatworkServerConfig.onSyncConfig(serverConfig)
        }

        // add more client-side message handlers here
    }
}
