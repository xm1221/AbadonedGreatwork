package cn.xm1221.abadoned_greatwork.mixin;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.PatternShapeMatch;
import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastResult;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapEvalTooMuch;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidPattern;
import at.petrak.hexcasting.api.casting.mishaps.MishapUnenlightened;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.common.casting.PatternRegistryManifest;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import cn.xm1221.abadoned_greatwork.casting.TestCastingEnv;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static at.petrak.hexcasting.api.utils.HexUtils.isOfTag;

@Mixin(PatternIota.class)
public abstract class PatternIotaMixin {


    //public abstract HexPattern getPattern();

    @WrapMethod(method = "execute")
    public @NotNull CastResult execute(CastingVM vm, ServerLevel world, SpellContinuation continuation, Operation<CastResult> original) {
        Supplier<@Nullable Component> castedName = () -> null;
            var lookup = PatternRegistryManifest.matchPattern(((PatternIota) (Object) this).getPattern(), vm.getEnv(), false);
            vm.getEnv().precheckAction(lookup);

            Action action;
            if (lookup instanceof PatternShapeMatch.Normal || lookup instanceof PatternShapeMatch.PerWorld) {
                ResourceKey<ActionRegistryEntry> key;
                if (lookup instanceof PatternShapeMatch.Normal normal) {
                    key = normal.key;
                } else {
                    PatternShapeMatch.PerWorld perWorld = (PatternShapeMatch.PerWorld) lookup;
                    key = perWorld.key;
                }

                var reqsEnlightenment = isOfTag(IXplatAbstractions.INSTANCE.getActionRegistry(), key,
                        HexTags.Actions.REQUIRES_ENLIGHTENMENT);

                castedName = () -> HexAPI.instance().getActionI18n(key, reqsEnlightenment);
                action = Objects.requireNonNull(IXplatAbstractions.INSTANCE.getActionRegistry().get(key)).action();
                if (action instanceof SpellAction && vm.getEnv() instanceof TestCastingEnv) {
                    //TODO:add a new mishap
                    vm.getEnv().printMessage(Component.translatable("abandoned_greatwork.test.mishap"));
                    return new CastResult(
                            ((PatternIota) (Object) this),
                            continuation,
                            null,
                            List.of(),
                            ResolvedPatternType.ERRORED,
                            HexEvalSounds.MISHAP);
                }
            }
        return original.call(vm, world, continuation);
    }

}
