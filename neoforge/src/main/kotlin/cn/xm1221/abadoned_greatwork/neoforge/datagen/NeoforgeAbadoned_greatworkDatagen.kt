package cn.xm1221.abadoned_greatwork.neoforge.datagen

import at.petrak.hexcasting.forge.datagen.TagsProviderEFHSetter
import cn.xm1221.abadoned_greatwork.datagen.Abadoned_greatworkActionTags
import net.minecraft.data.DataProvider
import net.minecraft.data.PackOutput
import net.neoforged.neoforge.data.event.GatherDataEvent

object NeoforgeAbadoned_greatworkDatagen {
    fun init(event: GatherDataEvent) {
        event.apply {
            if (System.getProperty("abadoned_greatwork.common-datagen") == "true") {
                // TODO: add datagen providers
            }

            if (System.getProperty("abadoned_greatwork.neoforge-datagen") == "true") {
                addVanillaProvider(includeServer()) { Abadoned_greatworkActionTags(it, lookupProvider) }
            }
        }
    }
}

private fun <T : DataProvider> GatherDataEvent.addProvider(run: Boolean, factory: (PackOutput) -> T) =
    generator.addProvider(run, DataProvider.Factory { factory(it) })

private fun <T : DataProvider> GatherDataEvent.addVanillaProvider(run: Boolean, factory: (PackOutput) -> T) =
    addProvider(run) { packOutput ->
        factory(packOutput).also {
            (it as TagsProviderEFHSetter).setEFH(existingFileHelper)
        }
    }
