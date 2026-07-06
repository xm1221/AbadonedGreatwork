package cn.xm1221.abadoned_greatwork.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.Clearable
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * 真理水晶方块实体 — 单格无GUI容器
 *
 * 特性：
 * - 内部只有一个物品槽位，可通过漏斗（hopper）等外部自动化方式交互
 * - 无 GUI，右键操作由 [interactions] 回调表调度
 * - 支持 NBT 持久化
 *
 * ## 交互回调机制
 * 手持特定物品右键真理水晶时，会查找 [interactions] 中为该 [Item] 注册的回调。
 * 若命中则执行回调，若未命中则什么都不发生（返回 PASS）。
 *
 * 注册回调示例：
 * ```kotlin
 * TruthCrystalBlockEntity.interactions[Items.DIAMOND] = InteractionCallback { be, player, hand, stack ->
 *     // 自定义逻辑 ...
 *     InteractionResult.SUCCESS
 * }
 * ```
 */
class TruthCrystalBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(type, pos, state), Container, Clearable {

    // ==================== 交互回调注册表 ====================

    companion object {
        /**
         * 交互回调映射表。
         *
         * **key**: 玩家手持的 [Item] 类型
         * **value**: 回调函数，参数依次为 方块实体 / 玩家 / 手 / 手持物品栈
         *
         * 回调返回 [InteractionResult.SUCCESS] 或 [InteractionResult.CONSUME]
         * 表示已处理交互；返回 [InteractionResult.PASS] 表示未处理。
         */
        val interactions: MutableMap<Item, InteractionCallback> = mutableMapOf(

        )
    }

    /**
     * 交互回调的函数式接口（SAM）。
     */
    fun interface InteractionCallback {
        fun onInteract(
            be: TruthCrystalBlockEntity,
            player: Player,
            hand: InteractionHand,
            heldStack: ItemStack,
        ): InteractionResult
    }

    // ==================== 容器实现 ====================

    private val items: NonNullList<ItemStack> = NonNullList.withSize(1, ItemStack.EMPTY)

    /** 对外暴露的槽位物品（只读） */
    val item: ItemStack get() = items[0]

    override fun getContainerSize(): Int = 1

    override fun isEmpty(): Boolean = item.isEmpty

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) setChanged()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        if (stack.count > maxStackSize) stack.count = maxStackSize
        setChanged()
    }

    override fun getMaxStackSize(): Int = 1

    override fun stillValid(player: Player): Boolean =
        if (level?.getBlockEntity(worldPosition) != this) false
        else player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = true

    // ==================== NBT 持久化 ====================

    override fun clearContent() {
        items.clear()
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        items.clear()
        ContainerHelper.loadAllItems(tag, items)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        ContainerHelper.saveAllItems(tag, items)
    }
}
