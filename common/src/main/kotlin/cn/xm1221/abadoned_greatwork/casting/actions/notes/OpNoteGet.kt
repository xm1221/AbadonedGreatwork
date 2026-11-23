package cn.xm1221.abadoned_greatwork.casting.actions.notes

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getInt
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import cn.xm1221.abadoned_greatwork.notes.NoteIota
import cn.xm1221.abadoned_greatwork.notes.NoteLibrary
import net.minecraft.server.level.ServerPlayer

/**
 * note/get：按 0-based 索引获取单个笔记条目（返回 NoteIota）。
 */
class OpNoteGet : ConstMediaAction {
    override val argc: Int get() = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapBadCaster()
        val index = args.getInt(0, argc)
        val entry = NoteLibrary.get(caster.server).getByIndex(caster.uuid, index)
            ?: throw MishapInvalidIota.of(args[0], 0, "note_index")
        return listOf(NoteIota(entry))
    }
}
