package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf

/**
 * 沙盒模式（「亲自一试」）提交绘制的图案到服务端安全环境执行。
 */
data class MsgPonderSandboxExecC2S(
    val sessionId: Int,
    val sig: String,
    val startDir: String,
    val imageNbt: CompoundTag,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgPonderSandboxExecC2S> {
        override val type = MsgPonderSandboxExecC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgPonderSandboxExecC2S = MsgPonderSandboxExecC2S(
            sessionId = buf.readInt(),
            sig = buf.readUtf(),
            startDir = buf.readUtf(),
            imageNbt = buf.readNbt() ?: CompoundTag(),
        )

        override fun MsgPonderSandboxExecC2S.encode(buf: FriendlyByteBuf) {
            buf.writeInt(sessionId)
            buf.writeUtf(sig)
            buf.writeUtf(startDir)
            buf.writeNbt(imageNbt)
        }
    }
}
