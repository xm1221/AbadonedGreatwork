package cn.xm1221.abadoned_greatwork.client.ponder

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

/**
 * 「问天」引导与检索系统快捷键（统一入口 `G`）：
 * - 平时按下 `G` 打开「问天」章节与《咒术笔记》检索目录；
 * - 佩戴探知透镜凝视带图案的石板或墙挂卷轴时按下 `G`，自动按该图案搜索对应章节与手册页。
 */
object PonderKeybinds {
    const val CATEGORY = "key.categories.abadoned_greatwork"

    @JvmField
    val OPEN_TIANWEN = KeyMapping(
        "key.abadoned_greatwork.tianwen",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        CATEGORY,
    )

    @JvmStatic
    fun allBinds(): Array<KeyMapping> = arrayOf(OPEN_TIANWEN)
}
