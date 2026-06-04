package cn.xm1221.abadoned_greatwork.fabric.datagen

import cn.xm1221.abadoned_greatwork.datagen.Abadoned_greatworkActionTags
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator

object FabricAbadoned_greatworkDatagen : DataGeneratorEntrypoint {
    override fun onInitializeDataGenerator(gen: FabricDataGenerator) {
        val pack = gen.createPack()

        pack.addProvider(::Abadoned_greatworkActionTags)
    }
}
