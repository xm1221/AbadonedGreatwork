package cn.xm1221.abadoned_greatwork.registry

import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.common.lib.HexRegistries
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import cn.xm1221.abadoned_greatwork.casting.iota.BiomeIota

/**
 * Iota 类型注册器。
 * 继承 [Abadoned_greatworkRegistrar]，向 Hex Casting 的 iota 类型注册表注册自定义 Iota。
 */
object Abadoned_greatworkIotaTypes : Abadoned_greatworkRegistrar<IotaType<*>>(
    HexRegistries.IOTA_TYPE,
    { HexIotaTypes.REGISTRY },
) {
    /** 封装群系/结构 ResourceLocation 的 Iota 类型 */
    val BIOME = register("biome") { BiomeIota.TYPE }
}
