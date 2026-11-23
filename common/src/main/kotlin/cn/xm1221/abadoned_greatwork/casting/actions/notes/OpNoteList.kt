package cn.xm1221.abadoned_greatwork.casting.actions.notes

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import cn.xm1221.abadoned_greatwork.notes.NoteIota
import cn.xm1221.abadoned_greatwork.notes.NoteLibrary
import net.minecraft.server.level.ServerPlayer

/**
 * note/list：获取施法者全部笔记条目列表（List<NoteIota>）。
 */
class OpNoteList : ConstMediaAction {
    override val argc: Int get() = 0

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapBadCaster()
        val entries = NoteLibrary.get(caster.server).entries(caster.uuid)
        return listOf(ListIota(entries.map { NoteIota(it) as Iota }))
    }
}
