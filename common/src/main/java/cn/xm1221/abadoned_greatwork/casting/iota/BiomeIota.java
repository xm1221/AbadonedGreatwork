package cn.xm1221.abadoned_greatwork.casting.iota;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Iota wrapping a biome or structure id.
 * Codec fields: {@code id} and {@code is_structure}.
 */
public class BiomeIota extends Iota {

    public static final String KEY_ID = "id";
    public static final String KEY_IS_STRUCTURE = "is_structure";

    private final ResourceLocation id;
    private final boolean structure;

    public BiomeIota(ResourceLocation id, boolean structure) {
        super(() -> TYPE);
        this.id = id;
        this.structure = structure;
    }

    public ResourceLocation getId() {
        return this.id;
    }

    public boolean isStructure() {
        return this.structure;
    }

    @Override
    public boolean isTruthy(@Nullable CastingEnvironment env) {
        return true;
    }

    @Override
    public boolean toleratesOther(Iota that) {
        return typesMatch(this, that)
            && that instanceof BiomeIota other
            && this.id.equals(other.id)
            && this.structure == other.structure;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, structure);
    }

    @Override
    public Component display() {
        String prefix = structure
            ? "text.abadoned_greatwork.biome_iota.structure"
            : "text.abadoned_greatwork.biome_iota.biome";
        return Component.translatable(prefix, Component.literal(id.toString()))
            .withStyle(ChatFormatting.ITALIC)
            .withStyle(ChatFormatting.AQUA);
    }

    public static final IotaType<BiomeIota> TYPE = new IotaType<>() {
        private static final MapCodec<BiomeIota> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf(KEY_ID).forGetter(BiomeIota::getId),
            Codec.BOOL.fieldOf(KEY_IS_STRUCTURE).forGetter(BiomeIota::isStructure)
        ).apply(instance, BiomeIota::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, BiomeIota> STREAM = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.<RegistryFriendlyByteBuf>cast(), BiomeIota::getId,
            ByteBufCodecs.BOOL.<RegistryFriendlyByteBuf>cast(), BiomeIota::isStructure,
            BiomeIota::new
        );

        @Override
        public MapCodec<BiomeIota> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, BiomeIota> streamCodec() {
            return STREAM;
        }

        @Override
        public int color() {
            return 0xff_00aaaa;
        }
    };
}
