package cn.xm1221.abadoned_greatwork.registry

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.HexRegistries
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpCheck
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpSort
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpTestEval


object Abadoned_greatworkActions : Abadoned_greatworkRegistrar<ActionRegistryEntry>(
    HexRegistries.ACTION,
    { HexActions.REGISTRY },
) {
    val TEST = make("test", HexDir.SOUTH_EAST,"qaqw", OpTestEval())
    val CHECK = make("check", HexDir.SOUTH_EAST,"qqqaqw", OpCheck())
    val SORT = make("sort", HexDir.NORTH_EAST,"awaq", OpSort())

    private fun make(name: String, startDir: HexDir, signature: String, action: Action) =
        make(name, startDir, signature) { action }

    private fun make(name: String, startDir: HexDir, signature: String, getAction: () -> Action) = register(name) {
        ActionRegistryEntry(HexPattern.fromAngles(signature, startDir), getAction())

    }
}
