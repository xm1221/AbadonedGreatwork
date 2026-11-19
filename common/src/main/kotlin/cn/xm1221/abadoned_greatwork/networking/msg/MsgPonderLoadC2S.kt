package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

/**
 * 客户端请求服务端预模拟并下发指定思索场景。
 */
data class MsgPonderLoadC2S(
    val sceneId: ResourceLocation,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgPonderLoadC2S> {
        override val type = MsgPonderLoadC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgPonderLoadC2S = MsgPonderLoadC2S(
            sceneId = buf.readResourceLocation(),
        )

        override fun MsgPonderLoadC2S.encode(buf: FriendlyByteBuf) {
            buf.writeResourceLocation(sceneId)
        }
    }
}
