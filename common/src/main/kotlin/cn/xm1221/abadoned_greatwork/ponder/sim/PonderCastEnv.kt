package cn.xm1221.abadoned_greatwork.ponder.sim

import at.petrak.hexcasting.api.casting.ParticleSpray
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.MishapEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate

/**
 * 思索安全模拟施法环境：
 * - 继承完整的 Hex Casting 施法语义，但禁止修改真实世界方块、不消耗媒质、不惩罚施法者；
 * - 捕获施法期间产生的揭示输出与 Mishap 事故消息，供思索界面的输出台实时展示；
 * - 允许演示卓越法术（`isEnlightened = true`），不写入玩家真实法杖栈或螺旋特效。
 */
class PonderCastEnv(
    world: ServerLevel,
    val player: ServerPlayer,
) : CastingEnvironment(world) {

    val capturedMessages: MutableList<Component> = ArrayList()

    override fun getCastingEntity(): LivingEntity = player

    override fun getMishapEnvironment(): MishapEnvironment = PonderMishapEnvironment(world, player)

    override fun mishapSprayPos(): Vec3 = player.eyePosition

    override fun isEnlightened(): Boolean = true

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long = 0L

    override fun isVecInRangeEnvironment(vec: Vec3?): Boolean = true

    override fun hasEditPermissionsAtEnvironment(pos: BlockPos?): Boolean = false

    override fun getCastingHand(): InteractionHand = InteractionHand.MAIN_HAND

    override fun getUsableStacks(mode: StackDiscoveryMode?): List<ItemStack> = emptyList()

    override fun getPrimaryStacks(): List<HeldItemInfo> = emptyList()

    override fun replaceItem(
        stackOk: Predicate<ItemStack>?,
        replaceWith: ItemStack?,
        hand: InteractionHand?,
    ): Boolean = false

    override fun getPigment(): FrozenPigment = IXplatAbstractions.INSTANCE.getPigment(player)

    override fun setPigment(pigment: FrozenPigment?): FrozenPigment = getPigment()

    override fun produceParticles(particles: ParticleSpray?, colorizer: FrozenPigment?) {
        // 教学模拟不在世界内喷射粒子
    }

    override fun printMessage(message: Component?) {
        if (message != null) {
            capturedMessages.add(message)
        }
    }

    private class PonderMishapEnvironment(
        world: ServerLevel,
        caster: ServerPlayer,
    ) : MishapEnvironment(world, caster) {
        override fun yeetHeldItemsTowards(targetPos: Vec3?) {}
        override fun dropHeldItems() {}
        override fun drown() {}
        override fun damage(healthProportion: Float) {}
        override fun removeXp(amount: Int) {}
        override fun blind(ticks: Int) {}
        override fun nauseate(ticks: Int) {}
    }
}
