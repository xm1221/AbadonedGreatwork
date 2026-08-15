package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getVec3
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.misc.MediaConstants
import cn.xm1221.abadoned_greatwork.casting.iota.BiomeIota
import cn.xm1221.abadoned_greatwork.entity.EyeOfLocating
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * 探古寻迹：接受一个 [BiomeIota]（目标）与一个向量 Iota（坐标），
 * 在目标坐标处召唤一只对目标群系/结构狩猎的探古之眼。
 *
 * 栈序（从栈顶）：BiomeIota（目标）在下，向量（坐标）在顶。
 */
class OpLocate : SpellAction {
    override val argc: Int
        get() = 2

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): SpellAction.Result {
        // 栈顶：目标坐标（向量）
        val spawnPos = args.getVec3(0, 2)
        // 栈次顶：目标（BiomeIota）
        val iota = args.getOrElse(1) { throw MishapNotEnoughArgs(2, args.size) }
        if (iota !is BiomeIota) throw MishapInvalidIota.ofType(iota, 1, "biome")

        // 召唤位置必须在施法范围内
        env.assertVecInRange(spawnPos)

        val id = iota.id
        val isStructure = iota.isStructure

        return SpellAction.Result(
            object : RenderedSpell {
                override fun cast(env: CastingEnvironment) {
                    val level = env.world

                    val eye = EyeOfLocating(level, spawnPos.x, spawnPos.y, spawnPos.z)
                    eye.setItem(ItemStack(Abadoned_greatworkItems.EYE_OF_LOCATING.value))
                    eye.setTarget(id, isStructure)
                    env.castingEntity?.uuid?.let { eye.setOwner(it) }
                    // 没有施法者视线，朝随机水平方向抛出（飞 12 格后悬停，靠大范围扫描确定方向）
                    val dir = Vec3(
                        level.random.nextDouble() * 2.0 - 1.0,
                        0.0,
                        level.random.nextDouble() * 2.0 - 1.0
                    ).normalize().scale(50.0)
                    eye.launchToward(dir)
                    level.addFreshEntity(eye)
                }
            },
            MediaConstants.CRYSTAL_UNIT,
            listOf()
        )
    }
}
