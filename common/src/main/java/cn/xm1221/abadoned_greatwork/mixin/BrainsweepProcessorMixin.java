package cn.xm1221.abadoned_greatwork.mixin;

import at.petrak.hexcasting.common.recipe.BrainsweepRecipe;
import at.petrak.hexcasting.interop.patchouli.BrainsweepProcessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vazkii.patchouli.api.IVariable;

import java.util.ArrayList;
import java.util.List;

@Mixin(BrainsweepProcessor.class)
public abstract class BrainsweepProcessorMixin {

    @Shadow(remap = false)
    @Nullable
    private BrainsweepRecipe recipe;

    @Unique
    @Nullable
    private IVariable abw$raidCaptainVar;

    @Unique
    private static final ResourceLocation ABW_WAVE_TRANSMITTER_RECIPE =
        new ResourceLocation("abadoned_greatwork", "brainsweep/wave_transmitter");

    @Inject(method = "process", at = @At("HEAD"), cancellable = true, remap = false)
    private void abw$customizeRaidCaptainPreview(
        Level level,
        String key,
        CallbackInfoReturnable<IVariable> cir
    ) {
        if (this.recipe == null || !ABW_WAVE_TRANSMITTER_RECIPE.equals(this.recipe.getId())) {
            return;
        }
        if ("entity".equals(key)) {
            if (this.abw$raidCaptainVar == null) {
                var clientLevel = Minecraft.getInstance().level;
                if (clientLevel == null) {
                    cir.setReturnValue(null);
                    return;
                }
                Pillager captain = EntityType.PILLAGER.create(clientLevel);
                if (captain == null) {
                    cir.setReturnValue(null);
                    return;
                }
                captain.setPatrolLeader(true);
                captain.setItemSlot(EquipmentSlot.HEAD, Raid.getLeaderBannerInstance());
                var tag = new CompoundTag();
                captain.save(tag);
                this.abw$raidCaptainVar = IVariable.wrap(
                    BuiltInRegistries.ENTITY_TYPE.getKey(captain.getType()) + tag.toString()
                );
            }
            cir.setReturnValue(this.abw$raidCaptainVar);
        } else if ("entityTooltip".equals(key)) {
            List<IVariable> lines = new ArrayList<>();
            lines.add(IVariable.from(Component.translatable("tooltip.abadoned_greatwork.raid_captain")));
            if (Minecraft.getInstance().options.advancedItemTooltips) {
                lines.add(
                    IVariable.from(
                        Component.literal("#abadoned_greatwork:raid_captains").withStyle(ChatFormatting.DARK_GRAY)
                    )
                );
            }
            cir.setReturnValue(IVariable.wrapList(lines));
        }
    }
}
