package cn.xm1221.abadoned_greatwork.neoforge

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.neoforge.datagen.NeoforgeAbadoned_greatworkDatagen
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.data.event.GatherDataEvent

@Mod(Abadoned_greatwork.MODID)
class NeoforgeAbadoned_greatwork(modBus: IEventBus, container: ModContainer) {
    init {
        setModEventBus(modBus)
        modBus.addListener { _: FMLDedicatedServerSetupEvent ->
            Abadoned_greatwork.initServer()
        }
        modBus.addListener { event: GatherDataEvent ->
            NeoforgeAbadoned_greatworkDatagen.init(event)
        }
        Abadoned_greatwork.init()
    }
}
