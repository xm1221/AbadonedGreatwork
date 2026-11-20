package cn.xm1221.abadoned_greatwork.item;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import at.petrak.hexcasting.common.items.storage.ItemFocus;
import cn.xm1221.abadoned_greatwork.api.HexPortNbt;
import cn.xm1221.abadoned_greatwork.api.ItemComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
    public static final String PONDER_HINTS = "ponder_hints";

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
        return ResourceLocation.fromNamespaceAndPath("abadoned_greatwork",
                "gameplay/truth_crystal_reward_" + lootTableIndex);
    }

    // ==================== Crafted ====================

    public static boolean isCrafted(ItemStack stack) {
        return ItemComponents.copyCustom(stack).getBoolean(IS_CRAFTED);
    }

    public static void markCrafted(ItemStack stack) {
        ItemComponents.editCustom(stack, tag -> tag.putBoolean(IS_CRAFTED, true));
    }

    // ==================== Length Limit & Counter ====================

    public static int getLengthLimit(ItemStack itemStack) {
        return ItemComponents.copyCustom(itemStack).getInt(LENGTH_LIMIT);
    }

    public static void setLengthLimit(ItemStack itemStack, int lengthLimit) {
        ItemComponents.editCustom(itemStack, tag -> tag.putInt(LENGTH_LIMIT, lengthLimit));
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
        var inputs = ItemComponents.copyCustom(itemStack).get(INPUT);
        if (!(inputs instanceof CompoundTag tag)) return 0;
        int count = 0;
        while (tag.contains(String.valueOf(count))) {
            count++;
        }
        return count;
    }

    public static Iterable<Iota> getInputAt(ItemStack itemStack, ServerLevel level, int index) {
        var inputs = ItemComponents.copyCustom(itemStack).get(INPUT);
        if (!(inputs instanceof CompoundTag tag)) return null;
        var listTag = tag.get(String.valueOf(index));
        if (!(listTag instanceof CompoundTag cTag)) return null;
        var iota = HexPortNbt.iotaFromNbt(cTag, level.registryAccess());
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
        inputs.put("0", HexPortNbt.iotaToNbt(list0));
        inputs.put("1", HexPortNbt.iotaToNbt(list1));
        ItemComponents.editCustom(itemStack, tag -> tag.put(INPUT, inputs));
    }

    public static void setInputs(ItemStack itemStack, List<ListIota> lists) {
        var inputs = new CompoundTag();
        for (int i = 0; i < lists.size(); i++) {
            inputs.put(String.valueOf(i), HexPortNbt.iotaToNbt(lists.get(i)));
        }
        ItemComponents.editCustom(itemStack, tag -> tag.put(INPUT, inputs));
    }

    // ==================== Output ====================

    public static void setOutput(ItemStack itemStack, ListIota list0, ListIota list1) {
        var output = new CompoundTag();
        output.put("0", HexPortNbt.iotaToNbt(list0));
        output.put("1", HexPortNbt.iotaToNbt(list1));
        ItemComponents.editCustom(itemStack, tag -> tag.put(OUTPUT, output));
    }

    public static void setOutputs(ItemStack itemStack, List<ListIota> lists) {
        var output = new CompoundTag();
        for (int i = 0; i < lists.size(); i++) {
            output.put(String.valueOf(i), HexPortNbt.iotaToNbt(lists.get(i)));
        }
        ItemComponents.editCustom(itemStack, tag -> tag.put(OUTPUT, output));
    }

    public static Iterable<Iota> getOutputAt(ItemStack itemStack, ServerLevel level, int index) {
        var outputs = ItemComponents.copyCustom(itemStack).get(OUTPUT);
        if (!(outputs instanceof CompoundTag tag)) return null;
        var listTag = tag.get(String.valueOf(index));
        if (!(listTag instanceof CompoundTag cTag)) return null;
        var iota = HexPortNbt.iotaFromNbt(cTag, level.registryAccess());
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
        ItemComponents.editCustom(itemStack, tag -> tag.putString(TOOLTIP, tooltip));
    }

    public static String getTooltip(ItemStack itemStack) {
        return ItemComponents.copyCustom(itemStack).getString(TOOLTIP);
    }

    public static void setMode(ItemStack itemStack, String mode) {
        ItemComponents.editCustom(itemStack, tag -> tag.putString(MODE, mode));
    }

    public static String getMode(ItemStack itemStack) {
        return ItemComponents.copyCustom(itemStack).getString(MODE);
    }

    public static void setPonderHints(ItemStack itemStack, List<String> hints) {
        ListTag list = new ListTag();
        for (String h : hints) {
            if (h != null && !h.isBlank()) list.add(StringTag.valueOf(h));
        }
        if (!list.isEmpty()) {
            ItemComponents.editCustom(itemStack, tag -> tag.put(PONDER_HINTS, list));
        }
    }

    public static List<String> getPonderHints(ItemStack itemStack) {
        Tag raw = ItemComponents.copyCustom(itemStack).get(PONDER_HINTS);
        if (!(raw instanceof ListTag list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    // ==================== Lifecycle ====================

    @Override
    public void writeDatum(ItemStack stack, @Nullable Iota iota) {
        setMode(stack, "eval");
        super.writeDatum(stack, iota);
    }

    @Override
    public boolean canWrite(ItemStack stack, Iota datum) {
        return ItemComponents.copyCustom(stack).getBoolean(CAN_WRITE);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        ItemComponents.editCustom(stack, tag -> tag.putBoolean(CAN_WRITE, true));
        markCrafted(stack);
    }

    // ==================== Tooltip display ====================

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> components,
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

        var text = ItemComponents.copyCustom(stack).getString(TOOLTIP);
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
