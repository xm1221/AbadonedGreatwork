package cn.xm1221.abadoned_greatwork.casting.actions.notes

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadCaster
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import cn.xm1221.abadoned_greatwork.notes.NoteIota
import cn.xm1221.abadoned_greatwork.notes.NoteLibrary
import cn.xm1221.abadoned_greatwork.notes.NoteSync
import cn.xm1221.abadoned_greatwork.notes.NoteValidator
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * note/import：接收单个 [NoteIota] 或 [ListIota]<NoteIota>，经安全校验后以新 ID 导入施法者笔记库并同步。
 */
class OpNoteImport : ConstMediaAction {
    override val argc: Int get() = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapBadCaster()
        val top = args.getOrElse(0) { throw MishapNotEnoughArgs(1, args.size) }
        val rawNotes: List<NoteIota> = when (top) {
            is NoteIota -> listOf(top)
            is ListIota -> {
                val out = mutableListOf<NoteIota>()
                for (elem in top.list) {
                    if (elem !is NoteIota) throw MishapInvalidIota.of(top, 0, "notes")
                    out.add(elem)
                }
                out
            }
            else -> throw MishapInvalidIota.of(top, 0, "notes")
        }

        val limits = NoteValidator.currentLimits()
        val lib = NoteLibrary.get(caster.server)
        var changed = false
        for (note in rawNotes) {
            val parsed = note.toEntry() ?: throw MishapInvalidIota.of(top, 0, "notes")
            val candidate = parsed.copy(id = UUID.randomUUID().toString())
            val res = NoteValidator.validateServer(candidate, env.world, limits)
            if (res is NoteValidator.ValidationResult.Ok) {
                if (lib.upsert(caster.uuid, res.entry, limits.maxEntriesPerPlayer)) {
                    changed = true
                }
            }
        }
        if (changed) {
            NoteSync.syncAll(caster)
        }
        return emptyList()
    }
}
