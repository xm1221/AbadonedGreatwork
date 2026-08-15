package cn.xm1221.abadoned_greatwork.casting.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.utils.HexUtils;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

/**
 * 封装群系或结构的 ResourceLocation 的 Iota。
 * <p>
 * 序列化格式：{ "id": "modid:xxx", "is_structure": 0b/1b }
 */
public class BiomeIota extends Iota {

    public static final String KEY_ID = "id";
    public static final String KEY_IS_STRUCTURE = "is_structure";

    private final ResourceLocation id;
    private final boolean isStructure;

    public BiomeIota(ResourceLocation id, boolean isStructure) {
        super(TYPE, Pair.of(id, isStructure));
        this.id = id;
        this.isStructure = isStructure;
    }

    /** 目标群系/结构的 ID */
    public ResourceLocation getId() {
        return this.id;
    }

    /** true 表示结构，false 表示群系 */
    public boolean isStructure() {
        return this.isStructure;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        return Iota.typesMatch(this, that)
            && that instanceof BiomeIota b
            && this.id.equals(b.id)
            && this.isStructure == b.isStructure;
    }

    @Override
    public @NotNull Tag serialize() {
        var tag = new CompoundTag();
        tag.putString(KEY_ID, this.id.toString());
        tag.putBoolean(KEY_IS_STRUCTURE, this.isStructure);
        return tag;
    }

    public static IotaType<BiomeIota> TYPE = new IotaType<>() {
        @Override
        public BiomeIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var compound = HexUtils.downcast(tag, CompoundTag.TYPE);
            var id = ResourceLocation.tryParse(compound.getString(KEY_ID));
            if (id == null) {
                throw new IllegalArgumentException("bad biome/structure id");
            }
            return new BiomeIota(id, compound.getBoolean(KEY_IS_STRUCTURE));
        }

        @Override
        public Component display(Tag tag) {
            var compound = HexUtils.downcast(tag, CompoundTag.TYPE);
            var id = compound.getString(KEY_ID);
            var isStruct = compound.getBoolean(KEY_IS_STRUCTURE);
            var prefix = isStruct
                ? "text.abadoned_greatwork.biome_iota.structure"
                : "text.abadoned_greatwork.biome_iota.biome";
            return Component.translatable(prefix,
                Component.literal(id)).withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.AQUA);
        }

        @Override
        public int color() {
            return 0xff_00aaaa;
        }
    };
}
