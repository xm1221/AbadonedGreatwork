package cn.xm1221.abadoned_greatwork.networking.msg

import cn.xm1221.abadoned_greatwork.client.renderer.AmbitBoundaryRenderer
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

/**
 * 同步当前维度中仍在激活态的道引标闭环标定线列表（S2C）。
 */
data class MsgSyncAmbitLinesS2C(
    val dimension: ResourceLocation,
    val loops: List<AmbitLineLoop>,
) : Abadoned_greatworkMessageS2C {

    data class AmbitLineLoop(
        val anchors: List<BlockPos>,
        val pigmentTag: CompoundTag?,
        val fallbackColor: Int,
    )

    companion object : Abadoned_greatworkMessageCompanion<MsgSyncAmbitLinesS2C> {
        override val type = MsgSyncAmbitLinesS2C::class.java

        override fun decode(buf: FriendlyByteBuf): MsgSyncAmbitLinesS2C {
            val dimension = buf.readResourceLocation()
            val loopCount = buf.readVarInt().coerceAtLeast(0)
            val loops = ArrayList<AmbitLineLoop>(loopCount)
            repeat(loopCount) {
                val anchorCount = buf.readVarInt().coerceAtLeast(0)
                val anchors = ArrayList<BlockPos>(anchorCount)
                repeat(anchorCount) {
                    anchors.add(buf.readBlockPos())
                }
                val pigmentTag = if (buf.readBoolean()) buf.readNbt() else null
                val fallbackColor = buf.readInt()
                loops.add(AmbitLineLoop(anchors, pigmentTag, fallbackColor))
            }
            return MsgSyncAmbitLinesS2C(dimension, loops)
        }

        override fun MsgSyncAmbitLinesS2C.encode(buf: FriendlyByteBuf) {
            buf.writeResourceLocation(dimension)
            buf.writeVarInt(loops.size)
            for (loop in loops) {
                buf.writeVarInt(loop.anchors.size)
                for (pos in loop.anchors) {
                    buf.writeBlockPos(pos)
                }
                val tag = loop.pigmentTag
                if (tag != null) {
                    buf.writeBoolean(true)
                    buf.writeNbt(tag)
                } else {
                    buf.writeBoolean(false)
                }
                buf.writeInt(loop.fallbackColor)
            }
        }
    }
}
