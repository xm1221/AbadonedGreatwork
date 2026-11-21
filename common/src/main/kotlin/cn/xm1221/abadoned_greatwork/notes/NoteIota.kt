package cn.xm1221.abadoned_greatwork.notes

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel

/**
 * 玩家笔记 Iota：一个 NoteIota 封装一条完整的 [NoteEntry]（含多页书页）。
 * 客户端展示严格基于 tag-only 读取，不触发任何世界状态反序列化。
 */
class NoteIota(data: CompoundTag) : Iota(TYPE, data) {

    constructor(entry: NoteEntry) : this(entry.toTag())

    val data: CompoundTag
        get() = payload as CompoundTag

    fun toEntry(): NoteEntry? = NoteEntry.fromTag(data)

    override fun isTruthy(): Boolean {
        val entry = toEntry() ?: return false
        return entry.title.isNotEmpty() || entry.pages.isNotEmpty()
    }

    override fun toleratesOther(that: Iota): Boolean =
        typesMatch(this, that) && that is NoteIota && this.data == that.data

    override fun serialize(): Tag = data.copy()

    companion object {
        val TYPE: IotaType<NoteIota> = object : IotaType<NoteIota>() {
            override fun deserialize(tag: Tag, world: ServerLevel?): NoteIota? {
                if (tag is CompoundTag) {
                    return NoteIota(tag.copy())
                }
                return null
            }

            override fun display(tag: Tag): Component {
                if (tag is CompoundTag) {
                    val title = tag.getString(NoteEntry.KEY_TITLE)
                    val author = tag.getString(NoteEntry.KEY_AUTHOR)
                    val pageCount = if (tag.contains(NoteEntry.KEY_PAGES, Tag.TAG_LIST.toInt())) {
                        tag.getList(NoteEntry.KEY_PAGES, Tag.TAG_COMPOUND.toInt()).size
                    } else if (tag.contains("body", Tag.TAG_STRING.toInt())) {
                        1
                    } else {
                        0
                    }
                    val titleText = title.ifBlank { Component.translatable("abadoned_greatwork.notes.unnamed").string }
                    val base = Component.literal(titleText).withStyle(ChatFormatting.AQUA)
                    val withAuthor = if (author.isNotBlank()) {
                        base.append(Component.literal(" — $author").withStyle(ChatFormatting.GRAY))
                    } else {
                        base
                    }
                    return withAuthor.append(Component.literal(" (${pageCount}p)").withStyle(ChatFormatting.DARK_GRAY))
                }
                return Component.translatable("abadoned_greatwork.notes.unnamed").withStyle(ChatFormatting.GRAY)
            }

            override fun color(): Int = 0xff_55ffff.toInt()
        }
    }
}
