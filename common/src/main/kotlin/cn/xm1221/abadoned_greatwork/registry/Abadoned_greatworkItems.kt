package cn.xm1221.abadoned_greatwork.registry

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/**
 * 物品注册器
 *
 * 负责注册本模组所有物品（含 BlockItem）。继承 [Abadoned_greatworkRegistrar]，
 * 使用 Minecraft 原版 Registries.ITEM 注册表。
 *
 * 与 [Abadoned_greatworkBlocks] 配合使用：方块注册器只注册 Block 本身，
 * BlockItem 需要在此注册器中单独注册。
 *
 * 使用示例：
 * ```kotlin
 * // 1. 注册普通物品
 * val myItem = Abadoned_greatworkItems.registerItem("my_item") {
 *     Item(Item.Properties())
 * }
 *
 * // 2. 为已注册方块注册 BlockItem
 * val myBlockItem = Abadoned_greatworkItems.registerBlockItem(myBlockEntry)
 *
 * // 3. 通过方块条目绑定方式（推荐，配合 registerBlockWithItem 使用）
 * val (blockEntry, itemSupplier) = Abadoned_greatworkBlocks.registerBlockWithItem("my_block") { ... }
 * val blockItemEntry = Abadoned_greatworkItems.registerItem(blockEntry.id) { itemSupplier() }
 * ```
 *
 * @see Abadoned_greatworkRegistrar 父类注册器框架
 * @see Abadoned_greatworkBlocks 方块注册器
 */
object Abadoned_greatworkItems : Abadoned_greatworkRegistrar<Item>(
    Registries.ITEM,
    { BuiltInRegistries.ITEM },
) {

    // ==================== 物品注册 ====================

    /** 真理水晶的 BlockItem */
    val TRUTH_CRYSTAL = registerBlockItem(Abadoned_greatworkBlocks.TRUTH_CRYSTAL)

   val ITEM_TRUTH_CRYSTAL = registerItem("item_truth_crystal", ItemTruthCrystal::getNew)

    /**
     * 注册一个普通物品。
     *
     * @param name 物品注册名，会自动补全为 [MODID]:[name] 格式的 ResourceLocation
     * @param itemSupplier 物品的懒加载工厂函数
     * @return 物品注册条目
     */
    fun registerItem(name: String, itemSupplier: () -> Item): RegistrarEntry<Item> =
        register(name, itemSupplier)

    /**
     * 为一个已注册的方块条目注册对应的 BlockItem。
     *
     * BlockItem 的注册 ID 与方块 ID 相同（这是 Minecraft 惯例）。
     *
     * @param blockEntry 来自 [Abadoned_greatworkBlocks] 的方块注册条目
     * @param properties BlockItem 的属性设置，默认使用默认属性
     * @return BlockItem 注册条目
     */
    fun registerBlockItem(
        blockEntry: RegistrarEntry<Block>,
        properties: Item.Properties = Item.Properties(),
    ): RegistrarEntry<Item> = register(blockEntry.id) {
        BlockItem(blockEntry.value, properties)
    }

    /**
     * 为多个方块条目批量注册 BlockItem。
     *
     * 所有 BlockItem 都使用默认的 [Item.Properties]。
     *
     * @param blockEntries 方块注册条目列表
     * @return BlockItem 注册条目列表
     */
    fun registerBlockItems(vararg blockEntries: RegistrarEntry<Block>): List<RegistrarEntry<Item>> =
        blockEntries.map { registerBlockItem(it) }
}
