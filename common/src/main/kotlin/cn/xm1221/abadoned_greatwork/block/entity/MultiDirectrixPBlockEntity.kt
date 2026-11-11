package cn.xm1221.abadoned_greatwork.block.entity

import at.petrak.hexcasting.api.addldata.ADIotaHolder
import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.math.HexPattern
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * 可编程分流器 `multi_directrix_p` 的方块实体：**存那条要执行的图案**，外加渲染用的染色。
 *
 * ## 为什么用 `HexBlockEntity`
 * 它已经把「存档 / 读档 / 同步」那套脚手架写好了：
 * `saveAdditional` → [saveModData]`、`getUpdateTag` → [saveModData]`、[sync]` = setChanged + 发包。
 * 所以这里只需要管自己的两个字段。
 *
 * ## 为什么实现 [ADIotaHolder]
 * 图案是**玩家用 Hex 自己的读写图案写进来**的（IoticBlocks 提供入口，见
 * `Abadoned_greatworkIotaHolders`）。实现这套接口 = 复用上游的写入 UX 和 NBT 格式，
 * 不用自己造一把「把 iota 塞进方块」的锤子。
 *
 * 只收 [PatternIota]（其余一律拒绝写入），因为分流器对本块做的是**执行**，
 * 不是存数据 —— 数字 / 列表写进来也没东西可执行。
 *
 * ## 染色
 * [tint] 由方块在推波时按**栈顶 iota 的类型**刷（借 Hex 自己的类型配色 `IotaType.color()`），
 * 写进 NBT 并 [sync] 到客户端，客户端拿它乘在 `_p_1` 那张灰阶贴图上。
 */
class MultiDirectrixPBlockEntity(
    type: BlockEntityType<*>,
    pos: BlockPos,
    state: BlockState,
) : HexBlockEntity(type, pos, state), ADIotaHolder {

    /** 方块里写入的图案；`null` = 没写，方块退化成普通 `multi_directrix`。 */
    var pattern: HexPattern? = null
        private set

    /** 渲染染色（`0xRRGGBB`），默认白 = 灰阶贴图原样。 */
    var tint: Int = DEFAULT_TINT
        private set

    /**
     * 刷染色。只在真的变了的时候 [sync]，免得每 tick 都往客户端推包。
     */
    fun setTint(rgb: Int) {
        if (tint == rgb) return
        tint = rgb
        sync()
    }

    // ==================== 存档 / 同步 ====================

    override fun saveModData(tag: CompoundTag) {
        pattern?.let { tag.put(TAG_PATTERN, it.serializeToNBT()) }
        tag.putInt(TAG_TINT, tint)
    }

    override fun loadModData(tag: CompoundTag) {
        pattern = if (tag.contains(TAG_PATTERN, Tag.TAG_COMPOUND.toInt())) {
            val sub = tag.getCompound(TAG_PATTERN)
            if (HexPattern.isPattern(sub)) HexPattern.fromNBT(sub) else null
        } else {
            null
        }
        tint = if (tag.contains(TAG_TINT)) tag.getInt(TAG_TINT) else DEFAULT_TINT
    }

    // ==================== ADIotaHolder ====================

    override fun readIotaTag(): CompoundTag? =
        pattern?.let { IotaType.serialize(PatternIota(it)) }

    /** 只收图案。别的 iota 一律「写不进去」（返回 `false`，玩家那边会看到写入失败）。 */
    override fun writeIota(iota: Iota?, simulate: Boolean): Boolean {
        if (iota !is PatternIota) return false
        if (!simulate) {
            pattern = iota.pattern
            setChanged()
            sync()
        }
        return true
    }

    override fun writeable(): Boolean = true

    companion object {
        private const val TAG_PATTERN = "pattern"
        private const val TAG_TINT = "tint"

        /** 没染过色：白色 = 贴图原样。 */
        const val DEFAULT_TINT: Int = 0xFFFFFF
    }
}
