package cn.xm1221.abadoned_greatwork.datagen

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.common.lib.HexRegistries
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkActions
import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.data.tags.TagsProvider
import java.util.concurrent.CompletableFuture

// see also: https://github.com/FallingColors/HexMod/blob/871f9387a3e1ccf0231a3e90c31e5d8472d46fde/Common/src/main/java/at/petrak/hexcasting/datagen/tag/HexActionTagProvider.java#L18
// (ignore the "ersatzActionTag" part, it doesn't seem to be necessary anymore)
class Abadoned_greatworkActionTags(
    output: PackOutput,
    provider: CompletableFuture<HolderLookup.Provider>,
) : TagsProvider<ActionRegistryEntry>(output, HexRegistries.ACTION, provider) {
    override fun addTags(provider: HolderLookup.Provider) {
        // per-world great spells

        }
    }

