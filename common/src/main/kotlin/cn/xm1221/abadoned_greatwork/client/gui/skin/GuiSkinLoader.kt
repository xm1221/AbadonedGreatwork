package cn.xm1221.abadoned_greatwork.client.gui.skin

import cn.xm1221.abadoned_greatwork.Abadoned_greatwork
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
        val found = rm.listResources("gui_skins") { it.namespace == Abadoned_greatwork.MODID && it.path.endsWith(".json") }
        for ((loc, res) in found) {
            val name = loc.path.removePrefix("gui_skins/").removeSuffix(".json")
            runCatching {
                res.open().use { input ->
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    val obj = JsonParser.parseString(text).asJsonObject
                    skins[name] = GuiSkin.fromJson(name, obj)
                }
            }.onFailure { err ->
                Abadoned_greatwork.LOGGER.warn("Failed to load GUI skin {}: {}", loc, err.message)
            }
        }
    }

    fun get(name: String): GuiSkin {
        skins[name]?.let { return it }
        // 若初始化阶段尚未经过一次 reload，按需直接从当前 resourceManager 读取一次
        val loc = ResourceLocation(Abadoned_greatwork.MODID, "gui_skins/$name.json")
        val rm = Minecraft.getInstance().resourceManager
        val res = rm.getResource(loc)
        if (res.isPresent) {
            val loaded = runCatching {
                res.get().open().use { input ->
                    val obj = JsonParser.parseString(input.readBytes().toString(Charsets.UTF_8)).asJsonObject
                    GuiSkin.fromJson(name, obj)
                }
            }.getOrNull()
            if (loaded != null) {
                skins[name] = loaded
                return loaded
            }
        }
        return GuiSkin.emptyFallback(name)
    }
}
