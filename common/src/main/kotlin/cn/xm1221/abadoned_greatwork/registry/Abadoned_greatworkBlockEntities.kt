package cn.xm1221.abadoned_greatwork.registry

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * 方块实体类型注册器
 *
 * 负责注册本模组所有方块实体类型（BlockEntityType）。
 * 继承 [Abadoned_greatworkRegistrar]，使用 Registries.BLOCK_ENTITY_TYPE 注册表。
 *
 * 与 [Abadoned_greatworkBlocks] 配合使用：方块注册器只注册 Block，
 * BlockEntityType 在此处单独注册并绑定到对应的 Block 实例。
 *
 * 使用示例：
 * ```kotlin
 * val myBE = Abadoned_greatworkBlockEntities.registerBE(
 *     name = "my_block_entity",
 *     blockEntityFactory = ::MyBlockEntity,
 *     blocks = Abadoned_greatworkBlocks.MY_BLOCK,
 * )
 * ```
 *
 * @see Abadoned_greatworkRegistrar 父类注册器框架
 * @see Abadoned_greatworkBlocks 方块注册器
 */
object Abadoned_greatworkBlockEntities : Abadoned_greatworkRegistrar<BlockEntityType<*>>(
    Registries.BLOCK_ENTITY_TYPE,
    { BuiltInRegistries.BLOCK_ENTITY_TYPE },
) {

    // ==================== 方块实体注册 ====================

    /** 真理水晶的 BlockEntityType */
    val TRUTH_CRYSTAL = registerBE(
        "truth_crystal",
        ::TruthCrystalBlockEntity,
        Abadoned_greatworkBlocks.TRUTH_CRYSTAL,
    )

    /**
     * 注册一个方块实体类型，并将其绑定到指定方块。
     *
     * 注意：由于 [BlockEntityType] 和 BlockEntity 构造函数之间存在循环依赖
     * （BE 构造需要 type，type 创建需要 BE factory），内部使用 object holder
     * 延迟绑定来解决此问题。
     *
     * @param name 注册名，会自动补全为 [MODID]:[name]
     * @param blockEntityFactory 方块实体的构造函数引用，签名为
     *   `(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) -> BlockEntity`，
     *   可直接传入 `::MyBlockEntity`
     * @param blocks 该方块实体类型所对应的方块条目，至少需要一个
     * @return 方块实体类型注册条目
     */
    @SafeVarargs
    fun registerBE(
        name: String,
        blockEntityFactory: (BlockEntityType<*>, BlockPos, BlockState) -> BlockEntity,
        vararg blocks: RegistrarEntry<Block>,
    ): RegistrarEntry<BlockEntityType<*>> = register(name) {
        // 解决 BlockEntityType ↔ BlockEntity 构造函数的循环依赖：
        // 先用 holder 占位，create supplier 先捕获 holder 引用，
        // 然后 build 得到 type 并回填到 holder，supplier 只在运行时 deref
        val holder = object { var type: BlockEntityType<*>? = null }
        val supplier = BlockEntityType.BlockEntitySupplier<BlockEntity> { pos, state ->
            blockEntityFactory(holder.type!!, pos, state)
        }
        holder.type = BlockEntityType.Builder.of(
            supplier,
            *blocks.map { it.value }.toTypedArray(),
        ).build(null) as BlockEntityType<*>
        holder.type!!
    }
}
