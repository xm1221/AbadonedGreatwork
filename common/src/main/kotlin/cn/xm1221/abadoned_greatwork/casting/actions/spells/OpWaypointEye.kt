package cn.xm1221.abadoned_greatwork.casting.actions.spells

import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getVec3
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.misc.MediaConstants
import cn.xm1221.abadoned_greatwork.entity.EyeOfWaypoint
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkItems
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * 召唤路标之眼：接受两个向量 Iota（栈顶为召唤位置，次顶为目的地），
 * 在指定位置召唤一只飞向目的地坐标的路标之眼。
 */
class OpWaypointEye : SpellAction {
    override val argc: Int
        get() = 2

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): SpellAction.Result {
        // 栈顶：召唤位置（向量）
        val spawnPos = args.getVec3(0, 2)
        // 栈次顶：目的地（向量）
        val waypoint = args.getVec3(1, 2)

        // 召唤位置必须在施法范围内
        env.assertVecInRange(spawnPos)

        return SpellAction.Result(
            object : RenderedSpell {
                override fun cast(env: CastingEnvironment) {
                    val level = env.world

                    val eye = EyeOfWaypoint(level, spawnPos.x, spawnPos.y, spawnPos.z)
                    eye.setItem(ItemStack(Abadoned_greatworkItems.EYE_OF_WAYPOINT.value))
                    eye.setTarget(EyeTarget.PositionTarget(BlockPos.containing(waypoint)))
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
