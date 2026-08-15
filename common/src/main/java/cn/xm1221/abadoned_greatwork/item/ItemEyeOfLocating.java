package cn.xm1221.abadoned_greatwork.item;

import cn.xm1221.abadoned_greatwork.entity.EyeOfLocating;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
 * 探古之眼物品 —— 仿末影之眼：右键抛出，召唤一只对指定（或随机）目标狩猎的探古之眼。
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
        if (!level.isClientSide) {
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
                var pair = randomTarget(level);
                id = pair.getFirst();
                isStructure = pair.getSecond();
            }

            var pos = player.getEyePosition();
            var eye = new EyeOfLocating(level, pos.x, pos.y - 0.1, pos.z);
            eye.setItem(new ItemStack(this));
            eye.setTarget(id, isStructure);
            eye.setOwner(player.getUUID());
            // 完全原版：朝视线方向抛出（飞 12 格后悬停）
            eye.launchToward(player.getLookAngle().scale(50));
            level.addFreshEntity(eye);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL,
            0.5f, 0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
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
