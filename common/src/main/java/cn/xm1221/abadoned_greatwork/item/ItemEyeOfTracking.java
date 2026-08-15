package cn.xm1221.abadoned_greatwork.item;

import cn.xm1221.abadoned_greatwork.entity.EyeOfTracking;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 追踪之眼物品 —— 右键抛出，召唤一只追踪 NBT 中记录的目标实体的追踪之眼。
 * <p>
 * NBT 键：{@link EyeOfTracking#KEY_TARGET_UUID}（目标实体 UUID）、
 * {@link EyeOfTracking#KEY_TARGET_NAME}（目标显示名，用于 tooltip）。
 * 实体碎裂时 50% 掉回此物品（带目标 NBT），可再次抛出。
 */
public class ItemEyeOfTracking extends Item {

    public ItemEyeOfTracking(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> components,
                                TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(EyeOfTracking.KEY_TARGET_NAME)) {
            components.add(Component.translatable("text.abadoned_greatwork.eye.tracking_target",
                Component.literal(tag.getString(EyeOfTracking.KEY_TARGET_NAME))
                    .withStyle(ChatFormatting.AQUA)));
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        ServerLevel serverLevel = (ServerLevel) level;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(EyeOfTracking.KEY_TARGET_UUID)) {
            return InteractionResultHolder.pass(stack); // 无目标，不消耗
        }

        Entity target = serverLevel.getEntity(tag.getUUID(EyeOfTracking.KEY_TARGET_UUID));
        if (target == null || !target.isAlive()) {
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.translatable("hexcasting.mishap.not_found",
                    tag.getString(EyeOfTracking.KEY_TARGET_NAME)));
            }
            return InteractionResultHolder.pass(stack); // 目标已消失，不消耗
        }

        var eye = new EyeOfTracking(level, player.getX(), player.getY(0.5), player.getZ());
        eye.setItem(stack.copyWithCount(1));
        eye.setTarget(new EyeTarget.EntityTarget(target));
        eye.setOwner(player.getUUID());
        eye.launchToward(player.getLookAngle().scale(50));
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
}
