package cn.xm1221.abadoned_greatwork.client.ponder.stage

import cn.xm1221.abadoned_greatwork.api.tianwen.TianwenApi
import cn.xm1221.abadoned_greatwork.api.tianwen.TianwenStageRenderer
import cn.xm1221.abadoned_greatwork.client.ponder.PonderGuiDraw
import cn.xm1221.abadoned_greatwork.ponder.data.StepSnapshot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.Vec2
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 内置「3D 方块微缩场景（world_3d）」舞台渲染器。
 *
 * 支持：
 * - 跨步累积的真实方块状态放置/替换/移除（`blocks`）；
 * - 3D 线框高亮（`highlights`）、能量连线与脉冲点（`links`）、物品点击提示（`use_item`）；
 * - 鼠标左键拖拽旋转视角（yaw/pitch）与滚轮缩放。
 */
class World3DStageRenderer : TianwenStageRenderer {

    override val id: String = TianwenApi.STAGE_WORLD_3D
    override val hasLeftPanel: Boolean = false
    override val supportsSandbox: Boolean = false

    private var innerX = 0
    private var innerY = 0
    private var innerW = 200
    private var innerH = 160

    private var yaw = -38f
    private var pitch = 28f
    private var zoom = 1.0f
    private var userAdjustedCamera = false
    private var lastCameraStep = -1
    private var dragging = false

    private data class BlockEntry(
        val x: Int,
        val y: Int,
        val z: Int,
        val state: BlockState,
        val animate: String = "none",
        val placedAtStep: Int = 0,
    )

    private data class HighlightEntry(
        val x: Int,
        val y: Int,
        val z: Int,
        val color: Int,
    )

    private data class LinkEntry(
        val x1: Float,
        val y1: Float,
        val z1: Float,
        val x2: Float,
        val y2: Float,
        val z2: Float,
        val color: Int,
        val pulse: Boolean,
    )

    private data class UseItemEntry(
        val x: Int,
        val y: Int,
        val z: Int,
        val itemId: String,
    )

    private data class AccumulatedWorld(
        val blocks: Map<Triple<Int, Int, Int>, BlockEntry>,
        val highlights: List<HighlightEntry>,
        val links: List<LinkEntry>,
        val useItem: UseItemEntry?,
        val caption: String,
        val camYaw: Float?,
        val camPitch: Float?,
        val camZoom: Float?,
    )

    override fun onLayout(innerX: Int, innerY: Int, innerW: Int, innerH: Int) {
        this.innerX = innerX
        this.innerY = innerY
        this.innerW = innerW
        this.innerH = innerH
    }

    override fun renderStage(
        mc: Minecraft,
        font: Font,
        g: GuiGraphics,
        innerX: Int,
        innerY: Int,
        innerW: Int,
        innerH: Int,
        snapshots: List<StepSnapshot>,
        currentStepIndex: Int,
        stepTicks: Float,
        partialTick: Float,
        playing: Boolean,
        speed: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        this.innerX = innerX
        this.innerY = innerY
        this.innerW = innerW
        this.innerH = innerH

        val world = accumulateWorld(snapshots, currentStepIndex)
        if (currentStepIndex != lastCameraStep) {
            lastCameraStep = currentStepIndex
            if (!userAdjustedCamera) {
                world.camYaw?.let { yaw = it }
                world.camPitch?.let { pitch = it }
                world.camZoom?.let { zoom = it }
            }
        }

        val (cx, cy, cz, scale) = computeBoundsAndScale(world.blocks.values)
        val snap = snapshots.getOrNull(currentStepIndex)
        val strokeProg = if (snap != null) {
            ((stepTicks + if (playing) partialTick * speed else 0f) / snap.strokeTicks.coerceAtLeast(1)).coerceIn(0f, 1f)
        } else 1f

        // 背景微弱底格感
        g.drawString(
            font,
            I18n.get("hexponder.ui.world3d_hint"),
            innerX + 6,
            innerY + innerH - 12,
            0x889C8FC0.toInt(),
            false,
        )

        if (world.caption.isNotEmpty()) {
            val cap = if (I18n.exists(world.caption)) I18n.get(world.caption) else world.caption
            val cw = font.width(cap) + 10
            val capX = innerX + (innerW - cw) / 2
            val capY = innerY + innerH - 28
            PonderGuiDraw.drawRecessedBox(g, capX, capY, cw, 14, 0xCC120E1D.toInt())
            g.drawString(font, cap, capX + 5, capY + 3, PonderGuiDraw.TEXT_BODY, true)
        }

        g.flush()
        val pose = g.pose()
        pose.pushPose()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        Lighting.setupFor3DItems()
        try {
            pose.translate(innerX + innerW * 0.5f, innerY + innerH * 0.52f, 140f)
            // 使用 (+scale, -scale, -scale) 保持右手系正行列式 (det > 0)，避免负缩放反转三角面绕序导致正面被剔除
            pose.scale(scale, -scale, -scale)
            pose.mulPose(Axis.XP.rotationDegrees(-pitch))
            pose.mulPose(Axis.YP.rotationDegrees(yaw))
            pose.translate(-cx - 0.5f, -cy - 0.5f, -cz - 0.5f)

            val buffers = g.bufferSource()
            val dispatcher = mc.blockRenderer

            for (blk in world.blocks.values) {
                var dropOffset = 0f
                var popScale = 1f
                if (blk.placedAtStep == currentStepIndex && strokeProg < 1f) {
                    if (blk.animate == "drop") {
                        dropOffset = (1f - strokeProg) * 0.85f
                    } else if (blk.animate == "pop") {
                        popScale = 0.35f + 0.65f * strokeProg
                    }
                }
                pose.pushPose()
                pose.translate(blk.x.toFloat(), blk.y.toFloat() + dropOffset, blk.z.toFloat())
                if (popScale != 1f) {
                    pose.translate(0.5f, 0.5f, 0.5f)
                    pose.scale(popScale, popScale, popScale)
                    pose.translate(-0.5f, -0.5f, -0.5f)
                }
                dispatcher.renderSingleBlock(
                    blk.state,
                    pose,
                    buffers,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                )
                pose.popPose()
            }

            // 3D 高亮线框
            val lineConsumer = buffers.getBuffer(RenderType.lines())
            for (hl in world.highlights) {
                val r = ((hl.color ushr 16) and 0xFF) / 255f
                val gr = ((hl.color ushr 8) and 0xFF) / 255f
                val b = (hl.color and 0xFF) / 255f
                LevelRenderer.renderLineBox(
                    pose,
                    lineConsumer,
                    hl.x - 0.03,
                    hl.y - 0.03,
                    hl.z - 0.03,
                    hl.x + 1.03,
                    hl.y + 1.03,
                    hl.z + 1.03,
                    r,
                    gr,
                    b,
                    0.95f,
                )
            }

            // 3D 连线与媒质波脉冲
            val timeFrac = ((System.currentTimeMillis() % 1400L) / 1400f)
            val mat = pose.last().pose()
            val norm = pose.last().normal()
            for (lnk in world.links) {
                val r = ((lnk.color ushr 16) and 0xFF) / 255f
                val gr = ((lnk.color ushr 8) and 0xFF) / 255f
                val b = (lnk.color and 0xFF) / 255f
                val fx = lnk.x1 + 0.5f
                val fy = lnk.y1 + 0.5f
                val fz = lnk.z1 + 0.5f
                val tx = lnk.x2 + 0.5f
                val ty = lnk.y2 + 0.5f
                val tz = lnk.z2 + 0.5f
                lineConsumer.vertex(mat, fx, fy, fz).color(r, gr, b, 0.9f).normal(norm, 0f, 1f, 0f).endVertex()
                lineConsumer.vertex(mat, tx, ty, tz).color(r, gr, b, 0.9f).normal(norm, 0f, 1f, 0f).endVertex()

                if (lnk.pulse) {
                    val px = Mth.lerp(timeFrac, fx, tx)
                    val py = Mth.lerp(timeFrac, fy, ty)
                    val pz = Mth.lerp(timeFrac, fz, tz)
                    LevelRenderer.renderLineBox(
                        pose,
                        lineConsumer,
                        px - 0.08,
                        py - 0.08,
                        pz - 0.08,
                        px + 0.08,
                        py + 0.08,
                        pz + 0.08,
                        1f,
                        0.95f,
                        0.5f,
                        1f,
                    )
                }
            }

            g.flush()
        } finally {
            Lighting.setupForFlatItems()
            RenderSystem.disableDepthTest()
            pose.popPose()
        }

        // 若当前步有 use_item，则在目标方块投影处渲染 2D 物品图标提示
        world.useItem?.let { ui ->
            val proj = projectBlockCenter(ui.x.toFloat(), ui.y.toFloat(), ui.z.toFloat(), cx, cy, cz, scale)
            val stack = PonderGuiDraw.resolveItemStack(ui.itemId)
            if (!stack.isEmpty) {
                val bob = sin((System.currentTimeMillis() % 1200L) / 1200.0 * Math.PI * 2).toFloat() * 2.5f
                g.renderItem(stack, (proj.x - 8).toInt(), (proj.y - 20 + bob).toInt())
            }
        }
    }

    override fun resolveAnchorScreenPos(
        anchor: String,
        anchorQ: Int?,
        anchorR: Int?,
        innerX: Int,
        innerY: Int,
        innerW: Int,
        innerH: Int,
        snapshots: List<StepSnapshot>,
        currentStepIndex: Int,
    ): Vec2? {
        if ((anchor == "block" || anchor == "coord") && anchorQ != null && anchorR != null) {
            val world = accumulateWorld(snapshots, currentStepIndex)
            val (cx, cy, cz, scale) = computeBoundsAndScale(world.blocks.values)
            val targetBlock = world.highlights.firstOrNull()
            val ty = targetBlock?.y?.toFloat() ?: 0f
            return projectBlockCenter(anchorQ.toFloat(), ty, anchorR.toFloat(), cx, cy, cz, scale)
        }
        return null
    }

    private fun projectBlockCenter(
        bx: Float,
        by: Float,
        bz: Float,
        cx: Float,
        cy: Float,
        cz: Float,
        scale: Float,
    ): Vec2 {
        val m = Matrix4f()
        m.translate(innerX + innerW * 0.5f, innerY + innerH * 0.52f, 140f)
        m.scale(scale, -scale, -scale)
        m.rotate(Axis.XP.rotationDegrees(-pitch))
        m.rotate(Axis.YP.rotationDegrees(yaw))
        m.translate(-cx - 0.5f, -cy - 0.5f, -cz - 0.5f)
        val v = Vector4f(bx + 0.5f, by + 0.5f, bz + 0.5f, 1f).mul(m)
        return Vec2(v.x, v.y)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && mouseX >= innerX && mouseX <= innerX + innerW && mouseY >= innerY && mouseY <= innerY + innerH) {
            dragging = true
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (dragging && button == 0) {
            userAdjustedCamera = true
            yaw += dragX.toFloat() * 0.75f
            pitch = (pitch + dragY.toFloat() * 0.65f).coerceIn(-75f, 80f)
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (dragging && button == 0) {
            dragging = false
            return true
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        if (mouseX >= innerX && mouseX <= innerX + innerW && mouseY >= innerY && mouseY <= innerY + innerH) {
            userAdjustedCamera = true
            zoom = (zoom + delta.toFloat() * 0.12f).coerceIn(0.45f, 2.4f)
            return true
        }
        return false
    }

    private fun computeBoundsAndScale(blocks: Collection<BlockEntry>): FloatArray {
        if (blocks.isEmpty()) return floatArrayOf(0f, 0f, 0f, 26f * zoom)
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (b in blocks) {
            if (b.x < minX) minX = b.x
            if (b.x > maxX) maxX = b.x
            if (b.y < minY) minY = b.y
            if (b.y > maxY) maxY = b.y
            if (b.z < minZ) minZ = b.z
            if (b.z > maxZ) maxZ = b.z
        }
        val span = maxOf(1, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1).toFloat()
        val base = min(innerW, innerH) * 0.56f / (span * 1.15f)
        return floatArrayOf(
            (minX + maxX) * 0.5f,
            (minY + maxY) * 0.5f,
            (minZ + maxZ) * 0.5f,
            base.coerceIn(10f, 44f) * zoom,
        )
    }

    private fun accumulateWorld(snapshots: List<StepSnapshot>, upTo: Int): AccumulatedWorld {
        val blocks = LinkedHashMap<Triple<Int, Int, Int>, BlockEntry>()
        var startIdx = upTo.coerceAtMost(snapshots.lastIndex).coerceAtLeast(0)
        while (startIdx > 0) {
            val s = snapshots[startIdx]
            if (s.stageId != id || s.clearCanvasBefore || s.stepType == "clear") break
            startIdx--
        }

        var highlights: List<HighlightEntry> = emptyList()
        var links: List<LinkEntry> = emptyList()
        var useItem: UseItemEntry? = null
        var caption = ""
        var camYaw: Float? = null
        var camPitch: Float? = null
        var camZoom: Float? = null

        for (i in startIdx..upTo.coerceAtMost(snapshots.lastIndex)) {
            val s = snapshots[i]
            if (s.stageId != id || s.stageDataJson.isBlank()) continue
            val obj = runCatching { JsonParser.parseString(s.stageDataJson).asJsonObject }.getOrNull() ?: continue

            if (obj.has("camera") && obj.get("camera").isJsonObject) {
                val c = obj.getAsJsonObject("camera")
                if (c.has("yaw")) camYaw = c.get("yaw").asFloat
                if (c.has("pitch")) camPitch = c.get("pitch").asFloat
                if (c.has("zoom")) camZoom = c.get("zoom").asFloat
            }
            if (obj.has("blocks") && obj.get("blocks").isJsonArray) {
                for (el in obj.getAsJsonArray("blocks")) {
                    if (!el.isJsonObject) continue
                    val bo = el.asJsonObject
                    val pos = bo.getAsJsonArray("pos") ?: continue
                    if (pos.size() < 3) continue
                    val x = pos[0].asInt
                    val y = pos[1].asInt
                    val z = pos[2].asInt
                    val blockId = bo.get("block")?.asString ?: "minecraft:air"
                    if (blockId == "minecraft:air") {
                        blocks.remove(Triple(x, y, z))
                    } else {
                        val state = parseBlockState(blockId, bo.getAsJsonObject("props"))
                        val anim = bo.get("animate")?.asString ?: "none"
                        blocks[Triple(x, y, z)] = BlockEntry(x, y, z, state, anim, i)
                    }
                }
            }
            if (i == upTo) {
                caption = obj.get("caption")?.asString ?: ""
                highlights = parseHighlights(obj)
                links = parseLinks(obj)
                useItem = parseUseItem(obj)
            }
        }
        return AccumulatedWorld(blocks, highlights, links, useItem, caption, camYaw, camPitch, camZoom)
    }

    private fun parseHighlights(obj: JsonObject): List<HighlightEntry> {
        val arr = obj.getAsJsonArray("highlights") ?: return emptyList()
        val out = ArrayList<HighlightEntry>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val pos = o.getAsJsonArray("pos") ?: continue
            if (pos.size() < 3) continue
            val col = parseHexColor(o.get("color")?.asString, 0xFF64C8FF.toInt())
            out.add(HighlightEntry(pos[0].asInt, pos[1].asInt, pos[2].asInt, col))
        }
        return out
    }

    private fun parseLinks(obj: JsonObject): List<LinkEntry> {
        val arr = obj.getAsJsonArray("links") ?: return emptyList()
        val out = ArrayList<LinkEntry>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val from = o.getAsJsonArray("from") ?: continue
            val to = o.getAsJsonArray("to") ?: continue
            if (from.size() < 3 || to.size() < 3) continue
            val col = parseHexColor(o.get("color")?.asString, 0xFFB886FF.toInt())
            val pulse = o.get("pulse")?.asBoolean ?: true
            out.add(LinkEntry(from[0].asFloat, from[1].asFloat, from[2].asFloat, to[0].asFloat, to[1].asFloat, to[2].asFloat, col, pulse))
        }
        return out
    }

    private fun parseUseItem(obj: JsonObject): UseItemEntry? {
        val u = obj.getAsJsonObject("use_item") ?: return null
        val pos = u.getAsJsonArray("pos") ?: return null
        if (pos.size() < 3) return null
        val item = u.get("item")?.asString ?: return null
        return UseItemEntry(pos[0].asInt, pos[1].asInt, pos[2].asInt, item)
    }

    private fun parseBlockState(blockId: String, props: JsonObject?): BlockState {
        val rl = ResourceLocation.tryParse(blockId) ?: return Blocks.STONE.defaultBlockState()
        val blk = BuiltInRegistries.BLOCK.get(rl)
        var state = blk.defaultBlockState()
        if (props != null) {
            val def = blk.stateDefinition
            for ((k, v) in props.entrySet()) {
                val prop = def.getProperty(k) ?: continue
                state = applyProperty(state, prop, v.asString)
            }
        }
        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Comparable<T>> applyProperty(state: BlockState, prop: Property<T>, raw: String): BlockState {
        val parsed = prop.getValue(raw)
        return if (parsed.isPresent) state.setValue(prop, parsed.get()) else state
    }

    private fun parseHexColor(s: String?, fallback: Int): Int {
        if (s.isNullOrBlank()) return fallback
        val clean = s.removePrefix("#").removePrefix("0x")
        val v = clean.toLongOrNull(16)?.toInt() ?: return fallback
        return if (clean.length <= 6) (0xFF shl 24) or v else v
    }
}
