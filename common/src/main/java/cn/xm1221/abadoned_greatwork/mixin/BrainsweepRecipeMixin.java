package cn.xm1221.abadoned_greatwork.mixin;

import at.petrak.hexcasting.common.recipe.BrainsweepRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BrainsweepRecipe.class)
public abstract class BrainsweepRecipeMixin {

    @Shadow(remap = false)
    @Final
    private ResourceLocation id;

    @Unique
    private static final ResourceLocation ABW_WAVE_TRANSMITTER_RECIPE =
        ResourceLocation.fromNamespaceAndPath("abadoned_greatwork", "brainsweep/wave_transmitter");

    @Inject(
        method = "matches(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/server/level/ServerLevel;)Z",
        at = @At("RETURN"),
        cancellable = true
    )
    private void abw$requireRaidCaptainForTransmitter(
        BlockState blockState,
        Entity entity,
        ServerLevel level,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (ABW_WAVE_TRANSMITTER_RECIPE.equals(this.id)) {
            boolean isCaptain = entity instanceof Raider raider
                && (raider.isPatrolLeader() || abw$isWearingOminousBanner(raider));
            if (!isCaptain) {
                cir.setReturnValue(false);
            }
        }
    }

    @Unique
    private static boolean abw$isWearingOminousBanner(Raider raider) {
        ItemStack head = raider.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && ItemStack.isSameItemSameComponents(
            head,
            Raid.getLeaderBannerInstance(raider.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN))
        );
    }
}
