package cn.xm1221.abadoned_greatwork.item;

import cn.xm1221.abadoned_greatwork.entity.EyeOfWaypoint;
import cn.xm1221.abadoned_greatwork.entity.eye.EyeTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
 * 路标之眼物品 —— 右键抛出，召唤一只飞向 NBT 中记录坐标的路标之眼。
 * <p>
 * NBT 键：{@link EyeOfWaypoint#KEY_TARGET_X} / {@link EyeOfWaypoint#KEY_TARGET_Y} /
 * {@link EyeOfWaypoint#KEY_TARGET_Z}（目标坐标）。
 * 实体碎裂时 50% 掉回此物品（带目标 NBT），可再次抛出。
 */
public class ItemEyeOfWaypoint extends Item {

    public ItemEyeOfWaypoint(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> components,
                                TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(EyeOfWaypoint.KEY_TARGET_X)) {
            BlockPos pos = new BlockPos(
                tag.getInt(EyeOfWaypoint.KEY_TARGET_X),
                tag.getInt(EyeOfWaypoint.KEY_TARGET_Y),
                tag.getInt(EyeOfWaypoint.KEY_TARGET_Z));
            components.add(Component.translatable("text.abadoned_greatwork.eye.waypoint_target",
                Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                    .withStyle(ChatFormatting.AQUA)));
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(EyeOfWaypoint.KEY_TARGET_X)) {
            return InteractionResultHolder.pass(stack); // 无目标，不消耗
        }
        BlockPos waypoint = new BlockPos(
            tag.getInt(EyeOfWaypoint.KEY_TARGET_X),
            tag.getInt(EyeOfWaypoint.KEY_TARGET_Y),
            tag.getInt(EyeOfWaypoint.KEY_TARGET_Z));

        var eye = new EyeOfWaypoint(level, player.getX(), player.getY(0.5), player.getZ());
        eye.setItem(stack.copyWithCount(1));
        eye.setTarget(new EyeTarget.PositionTarget(waypoint));
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
