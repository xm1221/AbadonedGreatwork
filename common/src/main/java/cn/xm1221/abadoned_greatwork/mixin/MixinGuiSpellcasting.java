package cn.xm1221.abadoned_greatwork.mixin;

import at.petrak.hexcasting.api.casting.eval.ResolvedPattern;
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.common.msgs.IMessage;
import at.petrak.hexcasting.common.msgs.MsgNewSpellPatternC2S;
import at.petrak.hexcasting.xplat.IClientXplatAbstractions;
import cn.xm1221.abadoned_greatwork.client.ponder.PonderSpellcastingAccess;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 轻量级 GuiSpellcasting 扩展，仅在 Hex Ponder 沙盒模式下启用：
 * - 拦截 drawEnd 的原版法杖发包，转交给本地回调发送到 Ponder 安全模拟环境；
 * - 覆盖 hexSize 与 coordsOffset，将网格精准限定并居中于带框的思索施法舞台内。
 * 图案染色则全部交由 MieLib 的 DrawPatternHook 处理，避免重复拦截 render。
 */
@Mixin(GuiSpellcasting.class)
public abstract class MixinGuiSpellcasting implements PonderSpellcastingAccess {

    @Shadow(remap = false)
    private List<ResolvedPattern> patterns;

    @Shadow(remap = false)
    private Set<HexCoord> usedSpots;

    @Unique
    private boolean sandboxMode$abw = false;

    @Unique
    private Consumer<HexPattern> onDrawPattern$abw = null;

    @Unique
    private float hexSizeOverride$abw = Float.NaN;

    @Unique
    private Vec2 coordsOffsetOverride$abw = null;

    @WrapWithCondition(
        method = "drawEnd",
        at = @At(
            value = "INVOKE",
            target = "Lat/petrak/hexcasting/xplat/IClientXplatAbstractions;sendPacketToServer(Lat/petrak/hexcasting/common/msgs/IMessage;)V",
            remap = false
        ),
        remap = false
    )
    private boolean redirectPattern$abw(IClientXplatAbstractions inst, IMessage msg) {
        if (sandboxMode$abw && msg instanceof MsgNewSpellPatternC2S s) {
            if (onDrawPattern$abw != null) {
                onDrawPattern$abw.accept(s.pattern());
            }
            return false;
        }
        return true;
    }

    @ModifyReturnValue(method = "hexSize", at = @At("RETURN"), remap = false)
    private float overrideHexSize$abw(float original) {
        return Float.isNaN(hexSizeOverride$abw) ? original : hexSizeOverride$abw;
    }

    @ModifyReturnValue(method = "coordsOffset", at = @At("RETURN"), remap = false)
    private Vec2 overrideCoordsOffset$abw(Vec2 original) {
        return coordsOffsetOverride$abw != null ? coordsOffsetOverride$abw : original;
    }

    @Override
    public void setSandboxMode$abw(boolean sandbox) {
        this.sandboxMode$abw = sandbox;
    }

    @Override
    public void setOnDrawPattern$abw(Consumer<HexPattern> callback) {
        this.onDrawPattern$abw = callback;
    }

    @Override
    public void setHexSizeOverride$abw(float hexSize) {
        this.hexSizeOverride$abw = hexSize;
    }

    @Override
    public void setCoordsOffsetOverride$abw(Vec2 offset) {
        this.coordsOffsetOverride$abw = offset;
    }

    @Override
    public void clearPatterns$abw() {
        patterns.clear();
        usedSpots.clear();
    }

    @Override
    public int patternCount$abw() {
        return patterns.size();
    }

    @Override
    public void setLastPatternType$abw(ResolvedPatternType type) {
        if (!patterns.isEmpty() && type != null) {
            patterns.get(patterns.size() - 1).setType(type);
        }
    }
}
