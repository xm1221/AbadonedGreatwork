package cn.xm1221.abadoned_greatwork.registry

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.MultiDirectrixBlock
import cn.xm1221.abadoned_greatwork.block.MultiDirectrixPBlock
import cn.xm1221.abadoned_greatwork.block.PlugSlateBlock
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
import net.minecraft.world.level.material.PushReaction

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
     * 多向导引石 — 实心本体（模型 `block/multi_directrix`），整格实心、无方块实体。
     *
     * 音效取 `DEEPSLATE`：上游的法术环件全部走 `HexBlocks.slateish()`
     * = `Properties.copy(Blocks.DEEPSLATE_TILES).strength(4f, 4f)`，
     * 也就是深板岩音效；本项目 `plug_slate` 早就是 DEEPSLATE，这里对齐同一套敲击声。
     */
    val MULTI_DIRECTRIX: RegistrarEntry<Block> = registerBlock("multi_directrix") {
        MultiDirectrixBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(3.0f, 6.0f)
                .sound(SoundType.DEEPSLATE)
                // 法术环件：活塞不许推，免得把环推坏
                .pushReaction(PushReaction.BLOCK),
        )
    }

    /**
     * 多向导引石·核心 — 八角骨架 + 暴露核心（模型 `block/multi_directrix_p`）。
     *
     * 骨架与核心之间留 4px 通缝，必须关掉遮挡剔除；贴图另有全透明区域，客户端走 cutout。
     */
    val MULTI_DIRECTRIX_P: RegistrarEntry<Block> = registerBlock("multi_directrix_p") {
        MultiDirectrixPBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(3.0f, 6.0f)
                .sound(SoundType.DEEPSLATE)
                .pushReaction(PushReaction.BLOCK)
                .noOcclusion(),
        )
    }

    /**
     * 插接石板 — 「底部 1px 平板 + 一侧 1px 竖墙」的 L 形角件（同一条板折 90°）。
     *
     * 共 16 个状态：4 个水平 `facing` × `half=bottom/top`（贴地 / 贴天花板）× `energized`。
     * 它是法术环的转角环件（`ICircleComponent`），只传递媒质波、不做运算。
     */
    val PLUG_SLATE: RegistrarEntry<Block> = registerBlock("plug_slate") {
        PlugSlateBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.DEEPSLATE)
                .strength(2.0f, 6.0f)
                .sound(SoundType.DEEPSLATE)
                .pushReaction(PushReaction.BLOCK)
                // L 形薄件：不参与整格遮挡，形状由 getShape 给出
                .noOcclusion(),
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
