package cn.xm1221.abadoned_greatwork.networking

import dev.architectury.networking.NetworkChannel
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.networking.msg.Abadoned_greatworkMessageCompanion

object Abadoned_greatworkNetworking {
    val CHANNEL: NetworkChannel = NetworkChannel.create(Abadoned_greatwork.id("networking_channel"))

    fun init() {
        for (subclass in Abadoned_greatworkMessageCompanion::class.sealedSubclasses) {
            subclass.objectInstance?.register(CHANNEL)
        }
    }
}
