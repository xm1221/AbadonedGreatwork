package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf

/**
 * 服务端向客户端同步玩家笔记列表与不可在笔记中直接按 op_id 引用的受限图案列表。
 */
data class MsgNoteLibrarySyncS2C(
    val entries: List<CompoundTag>,
    val excludedActions: List<String>,
) : Abadoned_greatworkMessageS2C {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteLibrarySyncS2C> {
        override val type = MsgNoteLibrarySyncS2C::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteLibrarySyncS2C {
            val count = buf.readVarInt()
            val entries = ArrayList<CompoundTag>(count)
            repeat(count) {
                entries.add(buf.readNbt() ?: CompoundTag())
            }
            val exCount = buf.readVarInt()
            val excluded = ArrayList<String>(exCount)
            repeat(exCount) {
                excluded.add(buf.readUtf(256))
            }
            return MsgNoteLibrarySyncS2C(entries, excluded)
        }

        override fun MsgNoteLibrarySyncS2C.encode(buf: FriendlyByteBuf) {
            buf.writeVarInt(entries.size)
            for (tag in entries) {
                buf.writeNbt(tag)
            }
            buf.writeVarInt(excludedActions.size)
            for (id in excludedActions) {
                buf.writeUtf(id, 256)
            }
        }
    }
}
