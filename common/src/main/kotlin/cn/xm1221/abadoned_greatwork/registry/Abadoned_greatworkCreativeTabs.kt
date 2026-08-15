package cn.xm1221.abadoned_greatwork.registry

import at.petrak.hexcasting.common.lib.HexItems
import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import cn.xm1221.abadoned_greatwork.block.TruthCrystalPuzzles
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ItemLike
import java.util.function.Supplier

/**
 * 创造模式物品栏注册器
 *
 * 负责注册本模组的创造模式物品栏标签页。继承 [Abadoned_greatworkRegistrar]，
 * 使用 Minecraft 原版 Registries.CREATIVE_MODE_TAB 注册表。
 *
 * 注意：Minecraft 1.20.1 中 [CreativeModeTab] 的构建方式与旧版不同，
 * 使用 builder 模式，需分别指定标题、图标和展示物品列表。
 *
 * 使用示例：
 * ```kotlin
 * // 创建一个物品栏标签页
 * val myTab = Abadoned_greatworkCreativeTabs.registerTab(
 *     name = "my_tab",
 *     titleKey = "itemGroup.abadoned_greatwork.my_tab",
 *     icon = { ItemStack(MyBlocks.SOME_BLOCK.value) },
 *     displayItems = { output ->
 *         // 将需要展示的物品/方块添加到 output 中
 *         output.accept(MyBlocks.SOME_BLOCK.value)
 *         output.accept(MyItems.SOME_ITEM.value)
 *     }
 * )
 * ```
 *
 * @see Abadoned_greatworkRegistrar 父类注册器框架
 * @see Abadoned_greatworkBlocks 方块注册器（用于获取展示物品）
 * @see Abadoned_greatworkItems 物品注册器（用于获取展示物品）
 */
object Abadoned_greatworkCreativeTabs : Abadoned_greatworkRegistrar<CreativeModeTab>(
    Registries.CREATIVE_MODE_TAB,
    { BuiltInRegistries.CREATIVE_MODE_TAB },
) {

    // ==================== 标签页注册 ====================

    /** 主标签页 — 展示本模组所有物品 */
    val MAIN = registerSimpleTab(
        name = "main",
        iconItem = { Abadoned_greatworkItems.TRUTH_CRYSTAL.value },
    ) { output ->
        output.accept(Abadoned_greatworkBlocks.TRUTH_CRYSTAL.value)
        output.accept(Abadoned_greatworkItems.TRUTH_CRYSTAL_0.value)
        output.accept(Abadoned_greatworkItems.TRUTH_CRYSTAL_1.value)
        output.accept(Abadoned_greatworkItems.TRUTH_CRYSTAL_2.value)
        output.accept(Abadoned_greatworkItems.EYE_OF_LOCATING.value)
        output.accept(Abadoned_greatworkItems.EYE_OF_TRACKING.value)
        output.accept(Abadoned_greatworkItems.EYE_OF_WAYPOINT.value)
        // 预定义谜题（每谜题 × 3 变体）
        for (stack in TruthCrystalPuzzles.ALL) {
            output.accept(stack)
        }
    }

    /**
     * 注册一个创造模式物品栏标签页。
     *
     * @param name 标签页注册名，会自动补全为 [MODID]:[name]
     * @param titleKey 标题的翻译键（trnaslation key），例如 "itemGroup.abadoned_greatwork.my_tab"
     * @param icon 标签页图标的 [ItemStack] 供应商
     * @param displayItems 用于向标签页中填充展示物品的回调，
     *   接收 [CreativeModeTab.Output] 参数，调用 output.accept(ItemLike) 添加物品
     * @return 创造物品栏标签页注册条目
     */
    @JvmName("registerTabWithItemStack")
    fun registerTab(
        name: String,
        titleKey: String,
        icon: Supplier<ItemStack>,
        displayItems: (CreativeModeTab.Output) -> Unit,
    ): RegistrarEntry<CreativeModeTab> = register(name) {
        CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM,0)
            .title(Component.translatable(titleKey))
            .icon(icon)
            .displayItems { _, output -> displayItems(output) }
            .build()
    }

    /**
     * 注册一个创造模式物品栏标签页（使用 [ItemLike] 作为图标，无需手动构造 ItemStack）。
     *
     * @param name 标签页注册名
     * @param titleKey 标题翻译键
     * @param iconItem 图标物品的 Supplier（通常是已注册方块/物品的 entry.value）
     * @param displayItems 展示物品填充回调
     * @return 创造物品栏标签页注册条目
     */
    @JvmName("registerTabWithItemLike")
    fun registerTab(
        name: String,
        titleKey: String,
        iconItem: Supplier<out ItemLike>,
        displayItems: (CreativeModeTab.Output) -> Unit,
    ): RegistrarEntry<CreativeModeTab> = registerTab(
        name = name,
        titleKey = titleKey,
        icon = Supplier { ItemStack(iconItem.get()) },
        displayItems = displayItems,
    )

    /**
     * 注册一个创造模式物品栏标签页的简便方法。
     *
     * 使用默认的翻译键格式 "itemGroup.[MODID].[name]"。
     *
     * @param name 标签页注册名
     * @param iconItem 图标物品的 Supplier
     * @param displayItems 展示物品填充回调
     * @return 创造物品栏标签页注册条目
     */
    fun registerSimpleTab(
        name: String,
        iconItem: Supplier<out ItemLike>,
        displayItems: (CreativeModeTab.Output) -> Unit,
    ): RegistrarEntry<CreativeModeTab> = registerTab(
        name = name,
        titleKey = "itemGroup.${Abadoned_greatwork.MODID}.$name",
        iconItem = iconItem,
        displayItems = displayItems,
    )
}
