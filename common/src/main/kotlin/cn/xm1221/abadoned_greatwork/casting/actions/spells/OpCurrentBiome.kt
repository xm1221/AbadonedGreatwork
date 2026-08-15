package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.casting.iota.BiomeIota

/**
 * 返回施法者当前所处群系的 [BiomeIota]。
 */
class OpCurrentBiome : ConstMediaAction {
    override val argc: Int
        get() = 0

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): List<Iota> {
        val level = env.world
        val pos = env.castingEntity?.blockPosition() ?: level.sharedSpawnPos
        val key = level.getBiome(pos).unwrapKey().orElse(null)
            ?: throw MishapNotFound("biome")
        return listOf(BiomeIota(key.location(), false))
    }
}
