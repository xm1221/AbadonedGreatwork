package cn.xm1221.abadoned_greatwork.api.tianwen

import cn.xm1221.abadoned_greatwork.ponder.data.StepSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.Vec2

/**
 * 「问天（Tianwen）」开放舞台渲染接口。
 *
 * 任何外部模组或内置模块都可以实现此接口，并在客户端初始化阶段通过
 * [TianwenApi.registerStageType] 注册新的演示舞台类型（如 3D 方块微缩场景、2D 物品交互流程、自定义仪器面板等）。
 */
interface TianwenStageRenderer {

    /** 舞台类型唯一标识（与 JSON 中的 `default_stage` / `step.stage` 对应）。 */
    val id: String

    /** 是否在画面左侧保留「施法栈与映像态」侧边栏；非网格舞台通常返回 `false` 以获得全宽舞台。 */
    val hasLeftPanel: Boolean get() = false

    /** 是否支持右下角「试绘」沙盒模式（仅咒术网格舞台需要）。 */
    val supportsSandbox: Boolean get() = false

    /** 每次切换到该舞台或窗口尺寸变化时重置/初始化视口区域。 */
    fun onLayout(innerX: Int, innerY: Int, innerW: Int, innerH: Int) {}

    /**
     * 渲染当前步的舞台内容（已在舞台裁剪区域 `enableScissor` 内部调用）。
     */
    fun renderStage(
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
    )

    /** 若当前舞台需要自绘浮窗 Tooltip（例如 2D 物品槽悬停），在裁剪区外部调用。 */
    fun renderOverlayTooltip(mc: Minecraft, font: Font, g: GuiGraphics, mouseX: Int, mouseY: Int) {}

    /** 解析浮动提示气泡（Callout）的目标锚点屏幕坐标；返回 null 则退回舞台中心。 */
    fun resolveAnchorScreenPos(
        anchor: String,
        anchorQ: Int?,
        anchorR: Int?,
        innerX: Int,
        innerY: Int,
        innerW: Int,
        innerH: Int,
        snapshots: List<StepSnapshot>,
        currentStepIndex: Int,
    ): Vec2? = null

    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean = false
    fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean = false
}
