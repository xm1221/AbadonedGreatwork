@file:JvmName("Abadoned_greatworkAbstractions")

package cn.xm1221.abadoned_greatwork

import dev.architectury.injectables.annotations.ExpectPlatform
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar

fun initRegistries(vararg registries: Abadoned_greatworkRegistrar<*>) {
    for (registry in registries) {
        initRegistry(registry)
    }
}

@ExpectPlatform
fun <T : Any> initRegistry(registrar: Abadoned_greatworkRegistrar<T>) {
    throw AssertionError()
}

/**
 * 将 edified 群系注册进主世界生成。
 * - Forge: BiomeManager.addBiome
 * - Fabric: OverworldBiomes.addContinentalBiome
 */
@ExpectPlatform
fun addEdifiedBiomeToOverworld() {
    throw AssertionError()
}
