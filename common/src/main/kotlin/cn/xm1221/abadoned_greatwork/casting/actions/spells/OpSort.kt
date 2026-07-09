package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getList
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota

class OpSort: ConstMediaAction {
    override val argc: Int
        get() = 1

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): List<Iota> {
        val list = args.getList(0,argc)
        val newlist= list.toMutableList()
        newlist.sortBy(Iota::hashCode)
        return listOf(ListIota(newlist))
    }
}