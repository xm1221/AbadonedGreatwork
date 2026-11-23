package cn.xm1221.abadoned_greatwork.casting.actions.notes

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getInt
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import cn.xm1221.abadoned_greatwork.notes.NoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteSync
import net.minecraft.server.level.ServerPlayer

/**
 * note/delete：按 0-based 索引删除一条笔记并同步客户端。
 */
class OpNoteDelete : ConstMediaAction {
    override val argc: Int get() = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapBadCaster()
        val index = args.getInt(0, argc)
        val lib = NoteLibrary.get(caster.server)
        if (!lib.deleteByIndex(caster.uuid, index)) {
            throw MishapInvalidIota.of(args[0], 0, "note_index")
        }
        NoteSync.syncAll(caster)
        return emptyList()
    }
}
