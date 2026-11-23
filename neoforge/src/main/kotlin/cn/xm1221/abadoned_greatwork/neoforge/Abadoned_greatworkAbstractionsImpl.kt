@file:JvmName("Abadoned_greatworkAbstractionsImpl")

package cn.xm1221.abadoned_greatwork.neoforge

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredRegister

private lateinit var modEventBus: IEventBus

/** Called from the mod constructor, before any registry is initialized. */
fun setModEventBus(bus: IEventBus) {
    modEventBus = bus
}

/**
 * NeoForge implementation of the common `@ExpectPlatform initRegistry`.
 *
 * Architectury resolves `initRegistry` to this class by the
 * `<common package>.<platform>.<file>Impl` naming convention. Each registrar gets its own
 * [DeferredRegister] on our own mod event bus, so entries keep the `abadoned_greatwork`
 * namespace.
 */
fun <T : Any> initRegistry(registrar: Abadoned_greatworkRegistrar<T>) {
    val deferred = DeferredRegister.create<T>(registrar.registryKey, Abadoned_greatwork.MODID)
    registrar.initLazy { id, supplier ->
        deferred.register(id.path, java.util.function.Supplier(supplier))
    }
    deferred.register(modEventBus)
}
