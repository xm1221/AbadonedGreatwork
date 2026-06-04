package cn.xm1221.abadoned_greatwork.networking.msg

import dev.architectury.networking.NetworkChannel
import dev.architectury.networking.NetworkManager.PacketContext
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.networking.Abadoned_greatworkNetworking
import cn.xm1221.abadoned_greatwork.networking.handler.applyOnClient
import cn.xm1221.abadoned_greatwork.networking.handler.applyOnServer
import net.fabricmc.api.EnvType
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import java.util.function.Supplier

sealed interface Abadoned_greatworkMessage

sealed interface Abadoned_greatworkMessageC2S : Abadoned_greatworkMessage {
    fun sendToServer() {
        Abadoned_greatworkNetworking.CHANNEL.sendToServer(this)
    }
}

sealed interface Abadoned_greatworkMessageS2C : Abadoned_greatworkMessage {
    fun sendToPlayer(player: ServerPlayer) {
        Abadoned_greatworkNetworking.CHANNEL.sendToPlayer(player, this)
    }

    fun sendToPlayers(players: Iterable<ServerPlayer>) {
        Abadoned_greatworkNetworking.CHANNEL.sendToPlayers(players, this)
    }
}

sealed interface Abadoned_greatworkMessageCompanion<T : Abadoned_greatworkMessage> {
    val type: Class<T>

    fun decode(buf: FriendlyByteBuf): T

    fun T.encode(buf: FriendlyByteBuf)

    fun apply(msg: T, supplier: Supplier<PacketContext>) {
        val ctx = supplier.get()
        when (ctx.env) {
            EnvType.SERVER, null -> {
                Abadoned_greatwork.LOGGER.debug("Server received packet from {}: {}", ctx.player.name.string, this)
                when (msg) {
                    is Abadoned_greatworkMessageC2S -> msg.applyOnServer(ctx)
                    else -> Abadoned_greatwork.LOGGER.warn("Message not handled on server: {}", msg::class)
                }
            }
            EnvType.CLIENT -> {
                Abadoned_greatwork.LOGGER.debug("Client received packet: {}", this)
                when (msg) {
                    is Abadoned_greatworkMessageS2C -> msg.applyOnClient(ctx)
                    else -> Abadoned_greatwork.LOGGER.warn("Message not handled on client: {}", msg::class)
                }
            }
        }
    }

    fun register(channel: NetworkChannel) {
        channel.register(type, { msg, buf -> msg.encode(buf) }, ::decode, ::apply)
    }
}
