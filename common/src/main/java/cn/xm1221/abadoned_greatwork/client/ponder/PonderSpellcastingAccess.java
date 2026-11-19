package cn.xm1221.abadoned_greatwork.client.ponder;

import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import net.minecraft.world.phys.Vec2;

import java.util.function.Consumer;

/**
 * 由 MixinGuiSpellcasting 实现，用于在 Hex Ponder「亲自一试」沙盒模式下嵌入原生 GuiSpellcasting。
 */
public interface PonderSpellcastingAccess {
    void setSandboxMode$abw(boolean sandbox);
    void setOnDrawPattern$abw(Consumer<HexPattern> callback);
    void setHexSizeOverride$abw(float hexSize);
    void setCoordsOffsetOverride$abw(Vec2 offset);
    void clearPatterns$abw();
    int patternCount$abw();
    void setLastPatternType$abw(ResolvedPatternType type);
}
