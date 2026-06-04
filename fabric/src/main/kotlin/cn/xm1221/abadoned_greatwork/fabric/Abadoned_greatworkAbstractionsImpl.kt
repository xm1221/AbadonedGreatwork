@file:JvmName("Abadoned_greatworkAbstractionsImpl")

package cn.xm1221.abadoned_greatwork.fabric

import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar
import net.minecraft.core.Registry

fun <T : Any> initRegistry(registrar: Abadoned_greatworkRegistrar<T>) {
    val registry = registrar.registry
    registrar.init { id, value -> Registry.register(registry, id, value) }
}
