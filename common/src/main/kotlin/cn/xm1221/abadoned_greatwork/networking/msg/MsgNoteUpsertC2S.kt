package cn.xm1221.abadoned_greatwork.networking.msg

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf

/**
 * 客户端新建或更新一条玩家笔记条目。
 */
data class MsgNoteUpsertC2S(
    val entryTag: CompoundTag,
) : Abadoned_greatworkMessageC2S {

    companion object : Abadoned_greatworkMessageCompanion<MsgNoteUpsertC2S> {
        override val type = MsgNoteUpsertC2S::class.java

        override fun decode(buf: FriendlyByteBuf): MsgNoteUpsertC2S = MsgNoteUpsertC2S(
            entryTag = buf.readNbt() ?: CompoundTag(),
        )

        override fun MsgNoteUpsertC2S.encode(buf: FriendlyByteBuf) {
            buf.writeNbt(entryTag)
        }
    }
}
