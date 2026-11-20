package cn.xm1221.abadoned_greatwork.api

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.ImageComponentType
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.casting.math.HexSignature
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.api.utils.TreeList
import at.petrak.hexcasting.common.lib.HexRegistries
import at.petrak.hexcasting.common.lib.hex.HexImageComponents
import cn.xm1221.abadoned_greatwork.registry.Abadoned_greatworkRegistrar
import com.mojang.serialization.Codec
import net.minecraft.core.HolderLookup
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult

/**
 * Image-component stand-in for the old CastingImage userData bag.
 * Ambit polygons and merged-impetus ids used to live in that compound.
 */
object AbadonedImageComponents : Abadoned_greatworkRegistrar<ImageComponentType<*>>(
    HexRegistries.IMAGE_COMPONENT,
    { HexImageComponents.REGISTRY },
) {
    val CIRCLE_META = register("circle_meta") {
        ImageComponentType(CompoundTag.CODEC, ByteBufCodecs.COMPOUND_TAG, false)
    }
}

fun lookupProvider(explicit: HolderLookup.Provider?): HolderLookup.Provider {
    if (explicit != null) return explicit
    return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
}

fun <T> encodeNbt(codec: Codec<T>, value: T, registries: HolderLookup.Provider? = null): Tag? {
    val ops = lookupProvider(registries).createSerializationContext(NbtOps.INSTANCE)
    return codec.encodeStart(ops, value).result().orElse(null)
}

fun <T> decodeNbt(codec: Codec<T>, tag: Tag, registries: HolderLookup.Provider? = null): T? {
    val ops = lookupProvider(registries).createSerializationContext(NbtOps.INSTANCE)
    return codec.parse(ops, tag).result().orElse(null)
}

fun iotaToNbt(iota: Iota, registries: HolderLookup.Provider? = null): CompoundTag =
    HexPortNbt.iotaToNbt(iota, registries)

fun iotaFromNbt(tag: Tag, registries: HolderLookup.Provider? = null): Iota? =
    HexPortNbt.iotaFromNbt(tag, registries)

object HexPortNbt {
    @JvmStatic
    @JvmOverloads
    fun iotaToNbt(iota: Iota, registries: HolderLookup.Provider? = null): CompoundTag {
        val tag = encodeNbt(IotaType.TYPED_CODEC, iota, registries)
        return tag as? CompoundTag ?: CompoundTag()
    }

    @JvmStatic
    @JvmOverloads
    fun iotaFromNbt(tag: Tag, registries: HolderLookup.Provider? = null): Iota? =
        decodeNbt(IotaType.TYPED_CODEC, tag, registries)
}

fun patternToNbt(pattern: HexPattern, registries: HolderLookup.Provider? = null): CompoundTag =
    encodeNbt(HexPattern.CODEC, pattern, registries) as? CompoundTag ?: CompoundTag()

fun patternFromNbt(tag: Tag, registries: HolderLookup.Provider? = null): HexPattern? =
    decodeNbt(HexPattern.CODEC, tag, registries)

fun pigmentToNbt(pigment: FrozenPigment, registries: HolderLookup.Provider? = null): CompoundTag =
    encodeNbt(FrozenPigment.CODEC, pigment, registries) as? CompoundTag ?: CompoundTag()

fun pigmentFromNbt(tag: Tag, registries: HolderLookup.Provider? = null): FrozenPigment? =
    decodeNbt(FrozenPigment.CODEC, tag, registries)

fun castingImageToNbt(image: CastingImage, registries: HolderLookup.Provider? = null): CompoundTag =
    encodeNbt(CastingImage.CODEC, image, registries) as? CompoundTag ?: CompoundTag()

fun castingImageFromNbt(tag: Tag, registries: HolderLookup.Provider? = null): CastingImage =
    decodeNbt(CastingImage.CODEC, tag, registries) ?: CastingImage()

fun patternFromAngles(signature: String, startDir: HexDir): HexPattern =
    HexPattern.fromAngleString(signature, startDir)

fun HexPattern.anglesSignature(): String = signature.toAnglesString()

val HexPattern.startDir: HexDir
    get() = orientation

/** Angle count of the signature. [HexSignature] has no exact size accessor. */
fun HexPattern.angleCount(): Int = signature.iterator().asSequence().count()

fun HexPattern.sigsEqual(other: HexPattern): Boolean = signature == other.signature

fun CastingImage.circleMeta(): CompoundTag =
    getComponent(AbadonedImageComponents.CIRCLE_META.value)?.copy() ?: CompoundTag()

fun CastingImage.withCircleMeta(tag: CompoundTag): CastingImage =
    if (tag.isEmpty) withoutComponent(AbadonedImageComponents.CIRCLE_META.value)
    else withComponent(AbadonedImageComponents.CIRCLE_META.value, tag.copy())

fun deepCopyComponents(components: Map<ImageComponentType<*>, Any>): Map<ImageComponentType<*>, Any> {
    val copied = LinkedHashMap<ImageComponentType<*>, Any>(components.size)
    for ((key, value) in components) {
        copied[key] = if (value is CompoundTag) value.copy() else value
    }
    return copied
}

fun CastingImage.withCopiedComponents(): CastingImage = copy(components = deepCopyComponents(components))

fun CastingImage.forkImage(): CastingImage = withCopiedComponents().withOverriddenUsedOps(0)

fun List<Iota>.asTreeList(): TreeList<Iota> = TreeList.from(this)

fun InteractionResult.asItemInteraction(): ItemInteractionResult = when (this) {
    InteractionResult.SUCCESS, InteractionResult.SUCCESS_NO_ITEM_USED -> ItemInteractionResult.SUCCESS
    InteractionResult.CONSUME -> ItemInteractionResult.CONSUME
    InteractionResult.CONSUME_PARTIAL -> ItemInteractionResult.CONSUME_PARTIAL
    InteractionResult.FAIL -> ItemInteractionResult.FAIL
    else -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
}

fun writeComponent(buf: FriendlyByteBuf, component: Component) {
    ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, component)
}

fun readComponent(buf: FriendlyByteBuf): Component =
    ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf)

fun iotaDisplay(tag: CompoundTag, registries: HolderLookup.Provider? = null): Component =
    iotaFromNbt(tag, registries)?.display() ?: Component.literal("<?>")

fun iotaColor(tag: CompoundTag, registries: HolderLookup.Provider? = null): Int =
    iotaFromNbt(tag, registries)?.type?.color() ?: 0xFF00DC
