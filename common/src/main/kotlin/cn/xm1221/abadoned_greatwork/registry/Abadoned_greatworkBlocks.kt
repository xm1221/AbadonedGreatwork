package cn.xm1221.abadoned_greatwork.registry

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.TruthCrystalBlock
import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor

/**
 * 方块注册器
 *
 * 负责注册本模组所有方块。继承 [Abadoned_greatworkRegistrar]，
 * 使用 Minecraft 原版 Registries.BLOCK 注册表。
 *
 * 使用示例：
 * ```kotlin
 * // 1. 注册纯方块（不自动生成 BlockItem）
 * val myBlock = Abadoned_greatworkBlocks.registerBlock("my_block") {
 *     Block(BlockBehaviour.Properties.of()
 *         .mapColor(MapColor.STONE)
 *         .strength(3.0f, 6.0f)
 *         .sound(SoundType.STONE))
 * }
 *
 * // 2. 注册方块并自动创建对应 BlockItem（需在 Abadoned_greatworkItems 配合处理）
 * val myBlock2 = Abadoned_greatworkBlocks.registerBlock("my_block_2") {
 *     Block(BlockBehaviour.Properties.of().strength(2.0f))
 * }
 * // 然后在 Abadoned_greatworkItems 中注册 BlockItem
 * Abadoned_greatworkItems.registerBlockItem(myBlock2)
 * ```
 *
 * @see Abadoned_greatworkRegistrar 父类注册器框架
 * @see Abadoned_greatworkItems 物品注册器（用于注册 BlockItem）
 */
object Abadoned_greatworkBlocks : Abadoned_greatworkRegistrar<Block>(
    Registries.BLOCK,
    { BuiltInRegistries.BLOCK },
) {

    // ==================== 方块注册 ====================

    /**
     * 真理水晶 — 单格无GUI容器方块。
     *
     * 右键时查找 [TruthCrystalBlockEntity.interactions] 中注册的回调并执行。
     */
    val TRUTH_CRYSTAL: RegistrarEntry<Block> = registerBlock("truth_crystal") {
        val beSupplier: () -> BlockEntityType<out BlockEntity> = {
            Abadoned_greatworkBlockEntities.TRUTH_CRYSTAL.value
        }
        TruthCrystalBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(3.0f, 6.0f)
                .sound(SoundType.AMETHYST)
                .noOcclusion(),
            beSupplier,
        )
    }

    /**
     * 注册一个方块。
     *
     * @param name 方块注册名，会自动补全为 [MODID]:[name] 格式的 ResourceLocation
     * @param blockSupplier 方块的懒加载工厂函数，在注册阶段才真正创建方块实例
     * @return 方块注册条目，包含 id、key 和延迟获取的 value
     */
    fun registerBlock(name: String, blockSupplier: () -> Block): RegistrarEntry<Block> =
        register(name, blockSupplier)

    /**
     * 注册一个方块，并同时构建对应的 BlockItem 工厂。
     *
     * 返回的 [BlockWithItem] 同时包含方块条目和 BlockItem 的构建函数，
     * 方便将 BlockItem 传递给 [Abadoned_greatworkItems] 统一注册。
     *
     * @param name 方块注册名
     * @param blockSupplier 方块的懒加载工厂函数
     * @param itemProperties BlockItem 的属性设置（默认使用默认属性）
     * @return [BlockWithItem] 包装类，包含方块条目和 BlockItem 工厂
     */
    fun registerBlockWithItem(
        name: String,
        blockSupplier: () -> Block,
        itemProperties: Item.Properties = Item.Properties(),
    ): BlockWithItem {
        val blockEntry = registerBlock(name, blockSupplier)
        return BlockWithItem(blockEntry) {
            BlockItem(blockEntry.value, itemProperties)
        }
    }

    /**
     * 方块与对应 BlockItem 的捆绑数据类。
     *
     * @property blockEntry 已注册的方块条目
     * @property blockItemSupplier BlockItem 的构建工厂
     */
    data class BlockWithItem(
        val blockEntry: RegistrarEntry<Block>,
        val blockItemSupplier: () -> BlockItem,
    )
}
