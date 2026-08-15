package cn.xm1221.abadoned_greatwork.item;

import cn.xm1221.abadoned_greatwork.entity.EyeOfLocating;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 探古之眼物品 —— 仿末影之眼：右键抛出。使用点（use）同步搜索目标，
 * 找到则召唤一只飞向目标的探古之眼；找不到则不消耗并提示。
 * <p>
 * 若物品 NBT 带有 "TargetId" 与 "IsStructure"，则狩猎该目标；否则随机挑选群系或结构。
 */
public class ItemEyeOfLocating extends Item {

    public static final String KEY_TARGET_ID = "TargetId";
    public static final String KEY_IS_STRUCTURE = "IsStructure";

    public ItemEyeOfLocating(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> components,
                                TooltipFlag flag) {
        //components.add(Component.translatable("text.abadoned_greatwork.eye.tooltip")
          //  .withStyle(ChatFormatting.GRAY));
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(KEY_TARGET_ID)) {
            String id = tag.getString(KEY_TARGET_ID);
            boolean isStructure = tag.getBoolean(KEY_IS_STRUCTURE);
            String key = isStructure
                ? "text.abadoned_greatwork.biome_iota.structure"
                : "text.abadoned_greatwork.biome_iota.biome";
            components.add(Component.translatable(key,
                Component.literal(id).withStyle(ChatFormatting.AQUA)));
        } else {
            components.add(Component.translatable("text.abadoned_greatwork.eye.random")
                .withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        ServerLevel serverLevel = (ServerLevel) level;

        // 目标：物品 NBT 指定，否则随机挑选群系或结构
        ResourceLocation id;
        boolean isStructure;
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(KEY_TARGET_ID)) {
            id = ResourceLocation.tryParse(tag.getString(KEY_TARGET_ID));
            isStructure = tag.getBoolean(KEY_IS_STRUCTURE);
            if (id == null) {
                id = new ResourceLocation("minecraft", "plains");
                isStructure = false;
            }
        } else {
            var pair = randomTarget(serverLevel);
            id = pair.getFirst();
            isStructure = pair.getSecond();
        }

        // 使用点同步搜索（与原版末影之眼投掷一致）：找到才生成眼睛，找不到不消耗
        BlockPos found = EyeOfLocating.searchTarget(serverLevel, player.blockPosition(), id, isStructure);
        if (found == null) {
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(
                    Component.translatable("hexcasting.mishap.not_found", id.toString()));
            }
            return InteractionResultHolder.pass(stack);
        }

        // 生成眼睛，瞄准已找到的目标（完全原版：signalTo 一次，飞 12 格后悬停）
        var eye = new EyeOfLocating(level, player.getX(), player.getY(0.5), player.getZ());
        eye.setItem(stack.copyWithCount(1));
        eye.setTarget(id, isStructure);
        eye.setOwner(player.getUUID());
        eye.aimAt(found);
        level.addFreshEntity(eye);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL,
            0.5f, 0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
        level.levelEvent(1003, player.blockPosition(), 0);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.success(stack);
    }

    /** 等概率随机挑选一个群系或结构 */
    private static com.mojang.datafixers.util.Pair<ResourceLocation, Boolean> randomTarget(Level level) {
        var access = ((ServerLevel) level).registryAccess();
        var rng = level.getRandom();
        if (rng.nextBoolean()) {
            var ids = access.registryOrThrow(Registries.STRUCTURE).keySet();
            var id = ids.stream().skip(rng.nextInt(Math.max(1, ids.size()))).findFirst()
                .orElse(new ResourceLocation("minecraft", "village_plains"));
            return com.mojang.datafixers.util.Pair.of(id, true);
        } else {
            var ids = access.registryOrThrow(Registries.BIOME).keySet();
            var id = ids.stream().skip(rng.nextInt(Math.max(1, ids.size()))).findFirst()
                .orElse(new ResourceLocation("minecraft", "plains"));
            return com.mojang.datafixers.util.Pair.of(id, false);
        }
    }
}
