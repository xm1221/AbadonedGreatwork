@file:JvmName("Abadoned_greatworkAbstractionsImpl")

package cn.xm1221.abadoned_greatwork.forge

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraftforge.common.BiomeManager
import net.minecraftforge.registries.RegisterEvent
import thedarkcolour.kotlinforforge.forge.MOD_BUS

fun <T : Any> initRegistry(registrar: Abadoned_greatworkRegistrar<T>) {
    MOD_BUS.addListener { event: RegisterEvent ->
        event.register(registrar.registryKey) { helper ->
            registrar.init(helper::register)
        }
    }
}

fun addEdifiedBiomeToOverworld() {
    val key = ResourceKey.create(Registries.BIOME, Abadoned_greatwork.EDIFIED_BIOME)
    BiomeManager.addBiome(
        BiomeManager.BiomeType.COOL,
        BiomeManager.BiomeEntry(key, 10)
    )
}
