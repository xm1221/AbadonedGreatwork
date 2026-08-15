@file:JvmName("Abadoned_greatworkAbstractionsImpl")

package cn.xm1221.abadoned_greatwork.forge

import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar
import net.minecraftforge.registries.RegisterEvent
import thedarkcolour.kotlinforforge.forge.MOD_BUS

fun <T : Any> initRegistry(registrar: Abadoned_greatworkRegistrar<T>) {
    MOD_BUS.addListener { event: RegisterEvent ->
        event.register(registrar.registryKey) { helper ->
            registrar.init(helper::register)
        }
    }
}
