package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import cn.xm1221.abadoned_greatwork.casting.iota.BiomeIota
import net.minecraft.core.registries.Registries

/**
 * 随机获取一个 [BiomeIota]。
 * 等概率从「群系」或「结构」注册表中随机挑一个目标封装为 [BiomeIota] 返回。
 */
class OpRandomBiome : ConstMediaAction {
    override val argc: Int
        get() = 0

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): List<Iota> {
        val access = env.world.registryAccess()
        val rng = env.world.random

        return if (rng.nextBoolean()) {
            // 随机结构
            val ids = access.registryOrThrow(Registries.STRUCTURE).keySet()
            if (ids.isEmpty()) throw MishapNotFound("structure")
            listOf(BiomeIota(ids.toList()[rng.nextInt(ids.size)], true))
        } else {
            // 随机群系
            val ids = access.registryOrThrow(Registries.BIOME).keySet()
            if (ids.isEmpty()) throw MishapNotFound("biome")
            listOf(BiomeIota(ids.toList()[rng.nextInt(ids.size)], false))
        }
    }
}
