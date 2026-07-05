package cn.xm1221.abadoned_greatwork.registry


import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks


class Abadoned_greatworkBlocks: Abadoned_greatworkRegistrar<Block>(
    Registries.BLOCK,
    { BuiltInRegistries.BLOCK},

) {
    private fun make(id: String,block: Block){
        register(id) { block }
    }
}