package cn.xm1221.abadoned_greatwork.networking.msg

import cn.xm1221.abadoned_greatwork.api.*
import cn.xm1221.abadoned_greatwork.ponder.data.readComponentList
import cn.xm1221.abadoned_greatwork.ponder.data.writeComponentList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component

/**
 * 服务端回传沙盒模式单步执行结果（含更新后的 CastingImage、结果类型、揭示与事故文本）。
 */
data class MsgPonderSandboxExecS2C(
    val sessionId: Int,
    val imageNbt: CompoundTag,
    val resolutionType: String,
    val consoleLines: List<Component>,
    val mishapText: Component?,
) : Abadoned_greatworkMessageS2C {

    companion object : Abadoned_greatworkMessageCompanion<MsgPonderSandboxExecS2C> {
        override val type = MsgPonderSandboxExecS2C::class.java

        override fun decode(buf: FriendlyByteBuf): MsgPonderSandboxExecS2C = MsgPonderSandboxExecS2C(
            sessionId = buf.readInt(),
            imageNbt = buf.readNbt() ?: CompoundTag(),
            resolutionType = buf.readUtf(),
            consoleLines = readComponentList(buf),
            mishapText = if (buf.readBoolean()) readComponent(buf) else null,
        )

        override fun MsgPonderSandboxExecS2C.encode(buf: FriendlyByteBuf) {
            buf.writeInt(sessionId)
            buf.writeNbt(imageNbt)
            buf.writeUtf(resolutionType)
            writeComponentList(buf, consoleLines)
            buf.writeBoolean(mishapText != null)
            if (mishapText != null) writeComponent(buf, mishapText)
        }
    }
}
