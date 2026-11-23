package cn.xm1221.abadoned_greatwork.client.gui.skin

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 屏幕内模态弹窗接口：打开时完全接管键鼠输入与 ESC 处理，隔离底层屏幕交互。
 */
interface SkinModal {
    fun setDismissHook(hook: () -> Unit) {}
    fun init(screenW: Int, screenH: Int)
    fun tick() {}
    fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float)
    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean
    fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean = false
    fun mouseMoved(mouseX: Double, mouseY: Double) {}
    fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = true
    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean
    fun charTyped(codePoint: Char, modifiers: Int): Boolean = false
    fun onClose() {}
}

/**
 * 带半透明暗幕、居中 `modal_panel` 边框与标题关闭按钮的基础模态浮层。
 */
abstract class BaseSkinModal(
    private val title: Component,
    private val regionKey: String,
    private val defaultW: Int,
    private val defaultH: Int,
    protected val onDismiss: () -> Unit,
) : SkinModal {

    protected val skin: GuiSkin get() = GuiSkinLoader.get("note_modals")
    protected val widgets = mutableListOf<AbstractWidget>()
    protected var focusedWidget: GuiEventListener? = null
    private var dismissHook: (() -> Unit)? = null

    protected var winX: Int = 0
    protected var winY: Int = 0
    protected var winW: Int = defaultW
    protected var winH: Int = defaultH

    override fun setDismissHook(hook: () -> Unit) {
        dismissHook = hook
    }

    override fun init(screenW: Int, screenH: Int) {
        val rect = skin.region(regionKey, SkinRect(0, 0, defaultW, defaultH))
        winW = minOf(rect.w, screenW - 12).coerceAtLeast(160)
        winH = minOf(rect.h, screenH - 12).coerceAtLeast(80)
        winX = (screenW - winW) / 2
        winY = (screenH - winH) / 2
        widgets.clear()
        focusedWidget = null

        addWidget(
            SkinButton({ skin }, winX + winW - 22, winY + 7, 14, 14, Component.empty(), "button_danger", "icon.delete") {
                dismiss()
            }
        )
        buildContent()
    }

    protected abstract fun buildContent()

    protected open fun renderContent(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {}

    protected open fun renderOverlay(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {}

    protected open fun shouldSuppressWidgetHover(): Boolean = false

    protected fun <T : AbstractWidget> addWidget(widget: T): T {
        widgets.add(widget)
        return widget
    }

    protected fun setModalFocus(target: GuiEventListener?) {
        focusedWidget?.isFocused = false
        focusedWidget = target
        target?.isFocused = true
    }

    protected fun dismiss() {
        onClose()
        dismissHook?.invoke()
        onDismiss()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val mc = Minecraft.getInstance()
        val sw = mc.window.guiScaledWidth
        val sh = mc.window.guiScaledHeight
        val s = skin

        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 400f)
        graphics.fill(0, 0, sw, sh, 0x88_000000.toInt())
        SkinRenderer.drawSprite(graphics, s, "modal_panel", winX, winY, winW, winH)
        graphics.drawString(mc.font, title, winX + 10, winY + 10, s.color("title", 0xFF_F2EBF9.toInt()), false)

        renderContent(graphics, mouseX, mouseY, partialTicks)
        val suppressHover = shouldSuppressWidgetHover()
        val wx = if (suppressHover) -1 else mouseX
        val wy = if (suppressHover) -1 else mouseY
        for (w in widgets) {
            w.render(graphics, wx, wy, partialTicks)
        }
        renderOverlay(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX < winX || mouseX >= winX + winW || mouseY < winY || mouseY >= winY + winH) {
            if (button == 0) dismiss()
            return true
        }
        for (w in widgets.asReversed()) {
            if (w.mouseClicked(mouseX, mouseY, button)) {
                setModalFocus(if (w is SkinTextField && w in widgets) w else null)
                return true
            }
        }
        setModalFocus(null)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        for (w in widgets.asReversed()) {
            if (w.mouseReleased(mouseX, mouseY, button)) return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        for (w in widgets.asReversed()) {
            if (w.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true
        }
        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dismiss()
            return true
        }
        if (focusedWidget?.keyPressed(keyCode, scanCode, modifiers) == true) return true
        return true
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (focusedWidget?.charTyped(codePoint, modifiers) == true) return true
        return false
    }
}
