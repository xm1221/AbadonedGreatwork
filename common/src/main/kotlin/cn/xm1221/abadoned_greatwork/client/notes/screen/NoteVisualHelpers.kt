package cn.xm1221.abadoned_greatwork.client.notes.screen

import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.client.render.PatternColors
import at.petrak.hexcasting.client.render.PatternRenderer
import at.petrak.hexcasting.client.render.PatternSettings
import cn.xm1221.abadoned_greatwork.notes.NotePageType
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap

/**
 * 笔记 GUI 共享视觉与渲染辅助：物品图标解析、实体 3D 安全预览、图案缩略渲染、背包纸张统计。
 */
object NoteVisualHelpers {

    private val entityCache = ConcurrentHashMap<String, Entity?>()
    private val failedEntities = ConcurrentHashMap.newKeySet<String>()

    fun clearEntityCache() {
        entityCache.clear()
        failedEntities.clear()
    }

    fun resolveItemStack(itemId: String): ItemStack {
        val rl = ResourceLocation.tryParse(itemId.trim()) ?: return ItemStack(Items.BOOK)
        val item = BuiltInRegistries.ITEM.get(rl)
        return ItemStack(if (item == Items.AIR) Items.BOOK else item)
    }

    fun countClientPaper(): Int {
        val player = Minecraft.getInstance().player ?: return 0
        var total = 0
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (stack.`is`(Items.PAPER)) total += stack.count
        }
        return total
    }

    fun isClientCreative(): Boolean =
        Minecraft.getInstance().player?.isCreative == true

    fun pageIconSuffix(type: NotePageType): String = when (type) {
        NotePageType.TEXT -> "text"
        NotePageType.SPOTLIGHT -> "spotlight"
        NotePageType.CRAFTING -> "crafting"
        NotePageType.SMELTING -> "smelting"
        NotePageType.HEX_PATTERN -> "pattern"
        NotePageType.HEX_MANUAL_PATTERN -> "manual_pattern"
        NotePageType.IMAGE -> "image"
        NotePageType.ENTITY -> "entity"
        NotePageType.LINK -> "link"
        NotePageType.EMPTY -> "empty"
        NotePageType.IOTA_DISPLAY -> "iota_display"
    }

    fun renderHexPattern(
        graphics: GuiGraphics,
        pattern: HexPattern,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        stroke: Double = 3.0,
        hexSize: Double = 14.0,
        key: String = "abw_note_pat",
    ) {
        val patSets = PatternSettings(
            key,
            PatternSettings.PositionSettings(
                w.toDouble(),
                h.toDouble(),
                2.0,
                2.0,
                PatternSettings.AxisAlignment.CENTER_FIT,
                PatternSettings.AxisAlignment.CENTER_FIT,
                hexSize,
                0.0,
                0.0,
            ),
            PatternSettings.StrokeSettings.fromStroke(stroke),
            PatternSettings.ZappySettings.STATIC,
        )
        graphics.pose().pushPose()
        graphics.pose().translate(x.toFloat(), y.toFloat(), 50f)
        PatternRenderer.renderPattern(pattern, graphics.pose(), patSets, PatternColors.READABLE_GRID_SCROLL_COLORS, 0.0, 3)
        graphics.pose().popPose()
    }

    /**
     * 安全在 GUI 内 3D 渲染实体预览（不含任何自定义 NBT，异常降级返回 false）。
     */
    fun renderEntityPreview(
        graphics: GuiGraphics,
        entityId: String,
        centerX: Float,
        centerY: Float,
        boxSize: Float,
        userScale: Float,
        rotationDeg: Float,
    ): Boolean {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val cleanId = entityId.substringBefore('{').trim()
        if (cleanId.isEmpty() || cleanId in failedEntities) return false

        val entity = entityCache[cleanId]?.takeIf { it.level() === level } ?: run {
            val rl = ResourceLocation.tryParse(cleanId) ?: run {
                failedEntities.add(cleanId)
                return false
            }
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                failedEntities.add(cleanId)
                return false
            }
            val created = runCatching {
                BuiltInRegistries.ENTITY_TYPE.get(rl).create(level)
            }.getOrNull()
            if (created == null) {
                failedEntities.add(cleanId)
                return false
            }
            entityCache[cleanId] = created
            created
        }

        return runCatching {
            val ew = entity.bbWidth.coerceAtLeast(0.4f)
            val eh = entity.bbHeight.coerceAtLeast(0.4f)
            val entitySize = maxOf(1f, maxOf(ew, eh))
            val renderScale = (boxSize / entitySize) * 0.72f * userScale.coerceIn(0.25f, 3.0f)
            val yOffset = maxOf(eh, entitySize) * 0.48f

            val ms = graphics.pose()
            ms.pushPose()
            ms.translate(centerX, centerY, 80f)
            ms.scale(renderScale, renderScale, renderScale)
            ms.translate(0f, yOffset, 0f)
            ms.mulPose(Axis.ZP.rotationDegrees(180f))
            ms.mulPose(Axis.YP.rotationDegrees(rotationDeg))

            val erd = mc.entityRenderDispatcher
            val immediate = mc.renderBuffers().bufferSource()
            erd.setRenderShadow(false)
            erd.render(entity, 0.0, 0.0, 0.0, 0f, 1f, ms, immediate, 0xF000F0)
            erd.setRenderShadow(true)
            immediate.endBatch()
            ms.popPose()
            true
        }.getOrElse {
            failedEntities.add(cleanId)
            false
        }
    }
}
