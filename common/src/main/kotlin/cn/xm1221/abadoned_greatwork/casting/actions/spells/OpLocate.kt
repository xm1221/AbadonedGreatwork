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
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * 探古寻迹：接受一个 [BiomeIota]（目标）与一个向量 Iota（召唤位置），
 * 在指定坐标召唤一只飞向目标群系/结构的探古之眼。
 *
 * execute 只做参数校验（廉价）；同步搜索放到 cast 阶段（主线程）——
 * 找到则在召唤位置生成眼睛并瞄准目标，找不到则不召唤（媒介已消耗，不抛 mishap）。
 *
 * 栈序（从栈顶）：向量（召唤位置）在下，BiomeIota（目标）在顶。
 */
class OpLocate : SpellAction {
    override val argc: Int
        get() = 2

    override fun execute(
        args: List<Iota>,
        env: CastingEnvironment
    ): SpellAction.Result {
        // 栈顶：召唤位置（向量）
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
                    val level = env.world as ServerLevel

                    // 使用点同步搜索（cast 阶段，主线程）：找到才召唤
                    val found = EyeOfLocating.searchTarget(
                        level, BlockPos.containing(spawnPos), id, isStructure)
                    if (found == null) {
                        // 找不到：提示但不抛 mishap（媒介已在 cast 前消耗）
                        if (env.castingEntity is ServerPlayer) {
                            (env.castingEntity as ServerPlayer).sendSystemMessage(
                                Component.translatable("hexcasting.mishap.not_found", id.toString()))
                        }
                        return
                    }

                    val eye = EyeOfLocating(level, spawnPos.x, spawnPos.y, spawnPos.z)
                    eye.setItem(ItemStack(Abadoned_greatworkItems.EYE_OF_LOCATING.value))
                    eye.setTarget(id, isStructure)
                    env.castingEntity?.uuid?.let { eye.setOwner(it) }
                    // 瞄准使用点搜索到的目标（完全原版：飞 12 格后悬停）
                    eye.aimAt(found)
                    level.addFreshEntity(eye)
                }
            },
            MediaConstants.CRYSTAL_UNIT,
            listOf()
        )
    }
}
