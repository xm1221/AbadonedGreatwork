package cn.xm1221.abadoned_greatwork.registry

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.HexRegistries
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpCheck
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpCurrentBiome
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpLocate
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpRandomBiome
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpSort
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpTestEval
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpTrackEye
import cn.xm1221.abadoned_greatwork.casting.actions.spells.OpWaypointEye


object Abadoned_greatworkActions : Abadoned_greatworkRegistrar<ActionRegistryEntry>(
    HexRegistries.ACTION,
    { HexActions.REGISTRY },
) {
    val TEST = make("test", HexDir.SOUTH_EAST,"qaqw", OpTestEval())
    val CHECK = make("check", HexDir.SOUTH_EAST,"qqqaqw", OpCheck())
    val SORT = make("sort", HexDir.NORTH_EAST,"awaq", OpSort())
    val LOCATE = make("locate", HexDir.WEST,"qqqqaqwawawww", OpLocate())
    val RANDOM_BIOME = make("random_biome", HexDir.SOUTH_WEST,"wwwawaeqqqq", OpRandomBiome())
    val CURRENT_BIOME = make("current_biome", HexDir.NORTH_EAST,"qqqqeawa", OpCurrentBiome())
    val TRACKING_EYE = make("tracking_eye", HexDir.WEST,"qqqqaqwawawwwqded", OpTrackEye())
    val WAYPOINT_EYE = make("waypoint_eye", HexDir.WEST,"qqqqaqwawawwweddwaa", OpWaypointEye())

    private fun make(name: String, startDir: HexDir, signature: String, action: Action) =
        make(name, startDir, signature) { action }

    private fun make(name: String, startDir: HexDir, signature: String, getAction: () -> Action) = register(name) {
        ActionRegistryEntry(HexPattern.fromAngles(signature, startDir), getAction())

    }
}
