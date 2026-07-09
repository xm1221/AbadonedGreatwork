package cn.xm1221.abadoned_greatwork.block

import at.petrak.hexcasting.api.item.IotaHolderItem
import cn.xm1221.abadoned_greatwork.block.entity.TruthCrystalBlockEntity
import cn.xm1221.abadoned_greatwork.item.ItemTruthCrystal
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * 真理水晶方块 — 带单格容器的无GUI方块
 *
 * ## 交互逻辑（右键）
 * 1. 空手 → 弹出内部水晶
 * 2. 手持真理水晶 → 放入（槽为空时）
 * 3. 手持 IotaHolderItem 且槽内有真理水晶 → 运行验证
 * 4. 查找自定义回调 → 若命中则执行
 *
 * ## 破坏掉落
 * 方块被破坏时会以掉落物形式弹出内部存储的物品。
 *
 * @param properties 方块属性（通过 [BlockBehaviour.Properties] 构建）
 * @param blockEntityTypeSupplier 方块实体类型的供应商，用于延迟绑定避免循环依赖
 */
class TruthCrystalBlock(
    properties: Properties,
    private val blockEntityTypeSupplier: () -> BlockEntityType<out BlockEntity>,
) : BaseEntityBlock(properties) {

    // ==================== BlockEntity ====================

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        blockEntityTypeSupplier().create(pos, state)!!

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    // ==================== 右键交互 ====================

    @Suppress("DEPRECATION")
    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val be = level.getBlockEntity(pos) as? TruthCrystalBlockEntity ?: return InteractionResult.PASS
        val heldStack = player.getItemInHand(hand)

        // 1. 空手 → 弹出内部水晶
        if (heldStack.isEmpty) {
            if (!be.isEmpty) {
                player.setItemInHand(hand, be.removeItemNoUpdate(0))
                return InteractionResult.SUCCESS
            }
            return InteractionResult.PASS
        }

        // 2. 手持真理水晶 → 放入（槽为空时）
        if (heldStack.item is ItemTruthCrystal) {
            if (be.isEmpty) {
                be.setItem(0, heldStack.split(1))
                return InteractionResult.SUCCESS
            }
            return InteractionResult.PASS
        }

        // 3. 手持 IotaHolderItem 且槽内有真理水晶 → 验证
        if (heldStack.item is IotaHolderItem && be.item.item is ItemTruthCrystal && level is ServerLevel) {
            return TruthCrystalInteraction.handle(be, player, level, heldStack)
        }

        // 4. 自定义回调
        val callback = TruthCrystalBlockEntity.interactions[heldStack.item]
        return callback?.onInteract(be, player, hand, heldStack) ?: InteractionResult.PASS
    }

    // ==================== 破坏掉落 ====================

    @Suppress("DEPRECATION")
    override fun onRemove(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        newState: BlockState,
        moved: Boolean,
    ) {
        if (!state.`is`(newState.block)) {
            val be = level.getBlockEntity(pos) as? TruthCrystalBlockEntity
            if (be != null && !be.isEmpty) {
                val dropPos = pos.center
                level.addFreshEntity(
                    ItemEntity(level, dropPos.x, dropPos.y, dropPos.z, be.item.copy())
                )
            }
            level.updateNeighbourForOutputSignal(pos, this)
        }
        super.onRemove(state, level, pos, newState, moved)
    }
}
