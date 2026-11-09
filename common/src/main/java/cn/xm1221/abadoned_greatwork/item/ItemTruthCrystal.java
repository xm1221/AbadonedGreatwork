package cn.xm1221.abadoned_greatwork.item;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
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

    // ==================== Length Limit & Counter ====================

    public static int getLengthLimit(ItemStack itemStack) {
        return NBTHelper.getInt(itemStack, LENGTH_LIMIT);
    }

    public static void setLengthLimit(ItemStack itemStack, int lengthLimit) {
        NBTHelper.putInt(itemStack, LENGTH_LIMIT, lengthLimit);
    }

    /**
     * 递归统计所有 Iota 的数量（含嵌套列表内部的元素），防止通过嵌套列表绕过长度上限。
     */
    public static int countTotalIotas(Iterable<Iota> iotas) {
        if (iotas == null) return 0;
        int count = 0;
        for (Iota iota : iotas) {
            count++;
            if (iota instanceof ListIota listIota) {
                count += countTotalIotas(listIota.getList());
            }
        }
        return count;
    }

    // ==================== Input ====================

    public static int getTestCount(ItemStack itemStack) {
        var inputs = NBTHelper.get(itemStack, INPUT);
        if (!(inputs instanceof CompoundTag tag)) return 0;
        int count = 0;
        while (tag.contains(String.valueOf(count))) {
            count++;
        }
        return count;
    }

    public static Iterable<Iota> getInputAt(ItemStack itemStack, ServerLevel level, int index) {
        var inputs = NBTHelper.get(itemStack, INPUT);
        if (!(inputs instanceof CompoundTag tag)) return null;
        var listTag = NBTHelper.get(tag, String.valueOf(index));
        if (!(listTag instanceof CompoundTag cTag)) return null;
        var iota = IotaType.deserialize(cTag, level);
        if (iota instanceof ListIota li) {
            return li.getList();
        }
        return null;
    }

    public static Iterable<Iota> getInput(ItemStack itemStack, ServerLevel level, Boolean bl) {
        return getInputAt(itemStack, level, bl ? 0 : 1);
    }

    public static void setInput(ItemStack itemStack, ListIota list0, ListIota list1) {
        var inputs = new CompoundTag();
        NBTHelper.put(inputs, "0", IotaType.serialize(list0));
        NBTHelper.put(inputs, "1", IotaType.serialize(list1));
        NBTHelper.put(itemStack, INPUT, inputs);
    }

    public static void setInputs(ItemStack itemStack, List<ListIota> lists) {
        var inputs = new CompoundTag();
        for (int i = 0; i < lists.size(); i++) {
            NBTHelper.put(inputs, String.valueOf(i), IotaType.serialize(lists.get(i)));
        }
        NBTHelper.put(itemStack, INPUT, inputs);
    }

    // ==================== Output ====================

    public static void setOutput(ItemStack itemStack, ListIota list0, ListIota list1) {
        var output = new CompoundTag();
        NBTHelper.put(output, "0", IotaType.serialize(list0));
        NBTHelper.put(output, "1", IotaType.serialize(list1));
        NBTHelper.put(itemStack, OUTPUT, output);
    }

    public static void setOutputs(ItemStack itemStack, List<ListIota> lists) {
        var output = new CompoundTag();
        for (int i = 0; i < lists.size(); i++) {
            NBTHelper.put(output, String.valueOf(i), IotaType.serialize(lists.get(i)));
        }
        NBTHelper.put(itemStack, OUTPUT, output);
    }

    public static Iterable<Iota> getOutputAt(ItemStack itemStack, ServerLevel level, int index) {
        var outputs = NBTHelper.get(itemStack, OUTPUT);
        if (!(outputs instanceof CompoundTag tag)) return null;
        var listTag = NBTHelper.get(tag, String.valueOf(index));
        if (!(listTag instanceof CompoundTag cTag)) return null;
        var iota = IotaType.deserialize(cTag, level);
        if (iota instanceof ListIota li) {
            return li.getList();
        }
        return null;
    }

    public static Iterable<Iota> getOutput(ItemStack itemStack, ServerLevel level, Boolean bl) {
        return getOutputAt(itemStack, level, bl ? 0 : 1);
    }

    // ==================== Tooltip / Mode ====================

    public static void setTooltip(ItemStack itemStack, String tooltip) {
        NBTHelper.putString(itemStack, TOOLTIP, tooltip);
    }

    public static String getTooltip(ItemStack itemStack) {
        return NBTHelper.getString(itemStack, TOOLTIP);
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
        ChatFormatting variantColor = lootTableIndex == 0 ? ChatFormatting.AQUA :
                (lootTableIndex == 1 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE);
        components.add(Component.translatable("text.abadoned_greatwork.crystal.variant", lootTableIndex)
                .withStyle(variantColor));

        int limit = getLengthLimit(stack);
        if (limit > 0) {
            components.add(Component.translatable("text.abadoned_greatwork.crystal.length", limit)
                    .withStyle(ChatFormatting.GRAY));
        }

        var text = NBTHelper.getString(stack, TOOLTIP);
        if (text != null && !text.isEmpty()) {
            components.add(Component.translatable(text).withStyle(ChatFormatting.YELLOW));
        } else {
            components.add(Component.translatable("text.abadoned_greatwork.crystal.tooltip"));
        }

        if (isCrafted(stack)) {
            components.add(Component.translatable("text.abadoned_greatwork.crystal.crafted")
                    .withStyle(ChatFormatting.DARK_GREEN));
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
