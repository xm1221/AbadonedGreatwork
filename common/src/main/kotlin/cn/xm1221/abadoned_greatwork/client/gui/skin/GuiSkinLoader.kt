package cn.xm1221.abadoned_greatwork.client.gui.skin

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.architectury.registry.ReloadListenerRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 皮肤 JSON 加载器：随客户端资源重载（F3+T）热加载 `assets/abadoned_greatwork/gui_skins/<name>.json`。
 * 支持通过 `"extends"` 字段继承公共皮肤并覆盖指定精灵、色板或布局区域。
 */
object GuiSkinLoader {

    private val skins = ConcurrentHashMap<String, GuiSkin>()
    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        ReloadListenerRegistry.register(
            PackType.CLIENT_RESOURCES,
            ResourceManagerReloadListener { rm -> reload(rm) },
        )
    }

    fun reload(rm: ResourceManager) {
        skins.clear()
        SkinRenderer.clearTextureCache()
        val rawObjects = mutableMapOf<String, JsonObject>()
        val found = rm.listResources("gui_skins") { it.namespace == Abadoned_greatwork.MODID && it.path.endsWith(".json") }
        for ((loc, res) in found) {
            val name = loc.path.removePrefix("gui_skins/").removeSuffix(".json")
            runCatching {
                res.open().use { input ->
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    rawObjects[name] = JsonParser.parseString(text).asJsonObject
                }
            }.onFailure { err ->
                Abadoned_greatwork.LOGGER.warn("Failed to parse GUI skin {}: {}", loc, err.message)
            }
        }
        for (name in rawObjects.keys) {
            resolveFromRaw(name, rawObjects, mutableSetOf())
        }
    }

    private fun resolveFromRaw(
        name: String,
        rawObjects: Map<String, JsonObject>,
        visiting: MutableSet<String>,
    ): GuiSkin {
        skins[name]?.let { return it }
        if (!visiting.add(name)) {
            Abadoned_greatwork.LOGGER.warn("Cyclic GUI skin extends detected at {}", name)
            return GuiSkin.emptyFallback(name)
        }
        val obj = rawObjects[name] ?: return GuiSkin.emptyFallback(name)
        val parentName = obj.get("extends")?.asString?.takeIf { it.isNotBlank() }
        val parentSkin = parentName?.let { resolveFromRaw(it, rawObjects, visiting) }
        val built = GuiSkin.fromJson(name, obj, parentSkin)
        skins[name] = built
        visiting.remove(name)
        return built
    }

    fun get(name: String): GuiSkin {
        skins[name]?.let { return it }
        val rm = Minecraft.getInstance().resourceManager
        return loadLazy(name, rm, mutableSetOf()) ?: GuiSkin.emptyFallback(name)
    }

    private fun loadLazy(name: String, rm: ResourceManager, visiting: MutableSet<String>): GuiSkin? {
        skins[name]?.let { return it }
        if (!visiting.add(name)) return null
        val loc = ResourceLocation.fromNamespaceAndPath(Abadoned_greatwork.MODID, "gui_skins/$name.json")
        val res = rm.getResource(loc)
        if (!res.isPresent) return null
        val obj = runCatching {
            res.get().open().use { input ->
                JsonParser.parseString(input.readBytes().toString(Charsets.UTF_8)).asJsonObject
            }
        }.getOrNull() ?: return null
        val parentName = obj.get("extends")?.asString?.takeIf { it.isNotBlank() }
        val parentSkin = parentName?.let { loadLazy(it, rm, visiting) }
        val built = GuiSkin.fromJson(name, obj, parentSkin)
        skins[name] = built
        visiting.remove(name)
        return built
    }
}
