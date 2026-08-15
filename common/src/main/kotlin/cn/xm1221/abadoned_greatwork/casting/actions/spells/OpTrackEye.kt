package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getEntity
import at.petrak.hexcasting.api.casting.getVec3
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.misc.MediaConstants
import cn.xm1221.abadoned_greatwork.entity.EyeOfTracking
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * 召唤追踪之眼：接受一个向量 Iota（召唤位置）与一个实体 Iota（目标），
 * 在指定位置召唤一只追踪该实体的追踪之眼。
 *
 * 栈序（从栈顶）：向量（召唤位置）在下，实体（目标）在顶。
 */
class OpTrackEye : SpellAction {
    override val argc: Int
        get() = 2

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): SpellAction.Result {
        // 栈顶：召唤位置（向量）
        val spawnPos = args.getVec3(0, 2)
        // 栈次顶：目标实体
        val targetEntity = args.getEntity(1, 2)

        // 召唤位置必须在施法范围内
        env.assertVecInRange(spawnPos)

        return SpellAction.Result(
            object : RenderedSpell {
                override fun cast(env: CastingEnvironment) {
                    val level = env.world

                    val eye = EyeOfTracking(level, spawnPos.x, spawnPos.y, spawnPos.z)
                    eye.setItem(ItemStack(Abadoned_greatworkItems.EYE_OF_TRACKING.value))
                    eye.setTarget(EyeTarget.EntityTarget(targetEntity))
                    env.castingEntity?.uuid?.let { eye.setOwner(it) }
                    // 没有施法者视线，朝随机水平方向抛出
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
