@file:JvmName("Abadoned_greatworkAbstractionsImpl")

package cn.xm1221.abadoned_greatwork.fabric

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar
import net.fabricmc.fabric.api.biome.v1.OverworldBiomes
import net.minecraft.core.Registry
import net.minecraft.world.level.biome.Climate

fun <T : Any> initRegistry(registrar: Abadoned_greatworkRegistrar<T>) {
    val registry = registrar.registry
    registrar.init { id, value -> Registry.register(registry, id, value) }
}

fun addEdifiedBiomeToOverworld() {
    // 中纬度内陆带：温和湿润的林地区域
    val point = Climate.parameters(
        Climate.Parameter.span(0.1f, 0.6f),   // temperature
        Climate.Parameter.span(-0.3f, 0.3f),  // humidity
        Climate.Parameter.span(0.3f, 0.8f),   // continentalness
        Climate.Parameter.span(-0.5f, 0.5f),  // erosion
        Climate.Parameter.span(0.0f, 1.0f),   // depth
        Climate.Parameter.span(-0.5f, 0.5f),  // weirdness
        0L
    )
    OverworldBiomes.addContinentalBiome(Abadoned_greatwork.EDIFIED_BIOME, point, 10)
}
