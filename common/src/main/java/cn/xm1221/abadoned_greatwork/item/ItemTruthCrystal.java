package cn.xm1221.abadoned_greatwork.item;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import at.petrak.hexcasting.api.utils.NBTHelper;
import at.petrak.hexcasting.common.items.storage.ItemFocus;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

public class ItemTruthCrystal extends ItemFocus {

    // ==================== NBT keys ====================

    public static final String LENGTH_LIMIT = "length_limit";
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String TOOLTIP = "tooltip";
    public static final String MODE = "mode";
    public static final String CAN_WRITE = "can_write";
    public static final String IS_CRAFTED = "is_crafted";

    // ==================== 3 kinds (reward loot tables) ====================

    public static final int VARIANT_0 = 0;
    public static final int VARIANT_1 = 1;
    public static final int VARIANT_2 = 2;

    public final int lootTableIndex;

    public ItemTruthCrystal(Properties pProperties) {
        this(pProperties, 0);
    }

    public ItemTruthCrystal(Properties pProperties, int lootTableIndex) {
        super(pProperties);
        this.lootTableIndex = lootTableIndex % 3;
    }

    public ResourceLocation getLootTable() {
        return new ResourceLocation("abadoned_greatwork",
                "gameplay/truth_crystal_reward_" + lootTableIndex);
    }

    // ==================== Crafted ====================

    public static boolean isCrafted(ItemStack stack) {
        return NBTHelper.getBoolean(stack, IS_CRAFTED);
    }

    public static void markCrafted(ItemStack stack) {
        NBTHelper.putBoolean(stack, IS_CRAFTED, true);
    }

    // ==================== Length Limit ====================

    public static int getLengthLimit(ItemStack itemStack) {
        return NBTHelper.getInt(itemStack, LENGTH_LIMIT);
    }

    public static void setLengthLimit(ItemStack itemStack, int lengthLimit) {
        NBTHelper.putInt(itemStack, LENGTH_LIMIT, lengthLimit);
    }

    // ==================== Input ====================

    public static Iterable<Iota> getInput(ItemStack itemStack, ServerLevel level, Boolean bl) {
        var inputs = NBTHelper.get(itemStack, INPUT);
        if (!(inputs instanceof CompoundTag tag)) return null;
        var list0 = NBTHelper.get(tag, "0");
        var list1 = NBTHelper.get(tag, "1");
        if (!(list0 instanceof CompoundTag tag0) || !(list1 instanceof CompoundTag tag1)) return null;
        var iota0 = IotaType.deserialize(tag0, level);
        var iota1 = IotaType.deserialize(tag1, level);
        if (iota0 instanceof ListIota li0 && iota1 instanceof ListIota li1) {
            return bl ? li0.getList() : li1.getList();
        }
        return null;
    }

    public static void setInput(ItemStack itemStack, ServerLevel level, ListIota list0, ListIota list1) {
        var inputs = new CompoundTag();
        NBTHelper.put(inputs, "0", list0.serialize());
        NBTHelper.put(inputs, "1", list1.serialize());
        NBTHelper.put(itemStack, INPUT, inputs);
    }

    // ==================== Output ====================

    public static void setOutput(ItemStack itemStack, ServerLevel level, ListIota list0, ListIota list1) {
        var output = new CompoundTag();
        NBTHelper.put(output, "0", list0.serialize());
        NBTHelper.put(output, "1", list1.serialize());
        NBTHelper.put(itemStack, OUTPUT, output);
    }

    public static Iterable<Iota> getOutput(ItemStack itemStack, ServerLevel level, Boolean bl) {
        var outputs = NBTHelper.get(itemStack, OUTPUT);
        if (!(outputs instanceof CompoundTag tag)) return null;
        var list0 = NBTHelper.get(tag, "0");
        var list1 = NBTHelper.get(tag, "1");
        if (!(list0 instanceof CompoundTag tag0) || !(list1 instanceof CompoundTag tag1)) return null;
        var iota0 = IotaType.deserialize(tag0, level);
        var iota1 = IotaType.deserialize(tag1, level);
        if (iota0 instanceof ListIota li0 && iota1 instanceof ListIota li1) {
            return bl ? li0.getList() : li1.getList();
        }
        return null;
    }

    // ==================== Tooltip / Mode ====================

    public static void setTooltip(ItemStack itemStack, String tooltip) {
        NBTHelper.putString(itemStack, TOOLTIP, tooltip);
    }

    public static void setMode(ItemStack itemStack, String mode) {
        NBTHelper.putString(itemStack, MODE, mode);
    }

    public static String getMode(ItemStack itemStack) {
        return NBTHelper.getString(itemStack, MODE);
    }

    // ==================== Lifecycle ====================

    @Override
    public void writeDatum(ItemStack stack, @Nullable Iota iota) {
        setMode(stack, "eval");
        super.writeDatum(stack, iota);
    }

    @Override
    public boolean canWrite(ItemStack stack, Iota datum) {
        return NBTHelper.getBoolean(stack, CAN_WRITE);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        NBTHelper.putBoolean(stack, CAN_WRITE, true);
        markCrafted(stack);
    }

    // ==================== Tooltip display ====================

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> components,
                                TooltipFlag flag) {
        // 1. 存储的 iota（仿照 IotaHolderItem.appendHoverText）
        IotaHolderItem.appendHoverText(this, stack, components, flag);

        // 2. 长度限制
        int limit = getLengthLimit(stack);
        if (limit > 0) {
            components.add(Component.translatable("text.abadoned_greatwork.crystal.length", limit)
                    .withStyle(ChatFormatting.GRAY));
        }

        // 3. 谜题提示
        var text = NBTHelper.getString(stack, TOOLTIP);
        if (text != null && !text.isEmpty()) {
            components.add(Component.translatable(text).withStyle(ChatFormatting.GOLD));
        }

        // 4. 合成标记
        if (isCrafted(stack)) {
            components.add(Component.translatable("text.abadoned_greatwork.crystal.crafted")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        // 5. 创造/高级模式：显示 input / output
        var tag = stack.getOrCreateTag();
        if (flag.isCreative() || flag.isAdvanced()) {
            if (level instanceof ServerLevel serverLevel) {
                if (tag.contains(INPUT)) {
                    components.add(Component.translatable("text.abadoned_greatwork.crystal.input_0")
                            .withStyle(ChatFormatting.AQUA));
                    showIotaList(getInput(stack, serverLevel, true), components);

                    components.add(Component.translatable("text.abadoned_greatwork.crystal.input_1")
                            .withStyle(ChatFormatting.AQUA));
                    showIotaList(getInput(stack, serverLevel, false), components);
                }
                if (tag.contains(OUTPUT)) {
                    components.add(Component.translatable("text.abadoned_greatwork.crystal.output_0")
                            .withStyle(ChatFormatting.GOLD));
                    showIotaList(getOutput(stack, serverLevel, true), components);

                    components.add(Component.translatable("text.abadoned_greatwork.crystal.output_1")
                            .withStyle(ChatFormatting.GOLD));
                    showIotaList(getOutput(stack, serverLevel, false), components);
                }
            }
        }
    }

    /** 使用 IotaType.getDisplay 格式化列表（仿照 IotaHolderItem 风格） */
    private static void showIotaList(@Nullable Iterable<Iota> iotas, List<Component> components) {
        if (iotas == null) return;
        int i = 0;
        for (Iota iota : iotas) {
            var tag = IotaType.serialize(iota);
            var display = IotaType.getDisplay(tag);
            components.add(Component.literal("  #" + i + ": ").append(display)
                    .withStyle(ChatFormatting.GRAY));
            i++;
        }
    }

    // ==================== Factory ====================

    public static ItemTruthCrystal getNew() {
        return new ItemTruthCrystal(new Properties().stacksTo(1));
    }

    public static ItemTruthCrystal create0() {
        return new ItemTruthCrystal(new Properties().stacksTo(1), VARIANT_0);
    }

    public static ItemTruthCrystal create1() {
        return new ItemTruthCrystal(new Properties().stacksTo(1), VARIANT_1);
    }

    public static ItemTruthCrystal create2() {
        return new ItemTruthCrystal(new Properties().stacksTo(1), VARIANT_2);
    }
}
