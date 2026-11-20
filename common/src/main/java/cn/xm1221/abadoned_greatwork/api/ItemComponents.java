package cn.xm1221.abadoned_greatwork.api;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Item NBT moved onto data components in 1.21. Custom item data lives in
 * {@link DataComponents#CUSTOM_DATA}; block-entity data placed with a block item
 * lives in {@link DataComponents#BLOCK_ENTITY_DATA}.
 */
public final class ItemComponents {
    private ItemComponents() {}

    public static CompoundTag copyCustom(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    public static CompoundTag customOrNull(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        return data.copyTag();
    }

    public static boolean hasCustom(ItemStack stack) {
        return customOrNull(stack) != null;
    }

    public static void setCustom(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag.copy()));
    }

    public static void editCustom(ItemStack stack, Consumer<CompoundTag> editor) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, editor);
    }

    public static CompoundTag blockEntityData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null || data.isEmpty()) return null;
        return data.copyTag();
    }
}
