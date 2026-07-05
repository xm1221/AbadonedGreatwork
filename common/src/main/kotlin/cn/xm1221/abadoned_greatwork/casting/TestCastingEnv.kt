package cn.xm1221.abadoned_greatwork.casting

import at.petrak.hexcasting.api.casting.ParticleSpray
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.MishapEnvironment
import at.petrak.hexcasting.api.pigment.FrozenPigment
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate

class TestCastingEnv(world: ServerLevel, val caster: LivingEntity?, val vec: Vec3?): CastingEnvironment(world) {

    override fun getCastingEntity(): LivingEntity? {
        return caster
    }

    override fun getMishapEnvironment(): MishapEnvironment {
        return TestMishapEnvironment(this)
    }

    override fun mishapSprayPos(): Vec3? {
        return vec
    }

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long {
        return 0
    }

    override fun isVecInRangeEnvironment(vec: Vec3?): Boolean {
        return true
    }

    override fun hasEditPermissionsAtEnvironment(pos: BlockPos?): Boolean {
        return true
    }

    override fun getCastingHand(): InteractionHand {
        return InteractionHand.MAIN_HAND
    }

    override fun getUsableStacks(mode: StackDiscoveryMode?): List<ItemStack?>? {
        return null
    }

    override fun getPrimaryStacks(): List<HeldItemInfo?>? {
        return null
    }

    override fun replaceItem(
        stackOk: Predicate<ItemStack?>?,
        replaceWith: ItemStack?,
        hand: InteractionHand?
    ): Boolean {
        return false
    }

    override fun getPigment(): FrozenPigment? {
        return null
    }

    override fun setPigment(pigment: FrozenPigment?): FrozenPigment? {
       return null
    }

    override fun produceParticles(
        particles: ParticleSpray?,
        colorizer: FrozenPigment?
    ) {
        pigment?.let { particles?.sprayParticles(this.world, it) };
    }

    override fun printMessage(message: Component?) {
        caster?.sendSystemMessage(message)
    }

    class TestMishapEnvironment(val env: TestCastingEnv) : MishapEnvironment(env.world, env.caster as ServerPlayer?) {
        override fun yeetHeldItemsTowards(targetPos: Vec3?) {

        }

        override fun dropHeldItems() {

        }

        override fun drown() {
        }

        override fun damage(healthProportion: Float) {

        }

        override fun removeXp(amount: Int) {

        }

        override fun blind(ticks: Int) {

        }
    }
}