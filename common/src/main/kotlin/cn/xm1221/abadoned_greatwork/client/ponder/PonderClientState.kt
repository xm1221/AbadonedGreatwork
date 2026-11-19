package cn.xm1221.abadoned_greatwork.client.ponder

import at.petrak.hexcasting.api.casting.math.HexCoord
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.common.lib.hex.HexActions
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderLoadC2S
import cn.xm1221.abadoned_greatwork.networking.msg.MsgPonderSandboxExecS2C
import cn.xm1221.abadoned_greatwork.ponder.data.PonderCategory
import cn.xm1221.abadoned_greatwork.ponder.data.PonderChunkHelper
import cn.xm1221.abadoned_greatwork.ponder.data.PonderIndexEntry
import cn.xm1221.abadoned_greatwork.ponder.data.PonderIndexPayload
import cn.xm1221.abadoned_greatwork.ponder.data.PonderPlaybackBundle
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.ResourceLocation

/**
 * 「问天」客户端状态与图案/章节索引中心。
 */
object PonderClientState {

    data class PatternSearchQuery(
        val pattern: HexPattern,
        val actionId: ResourceLocation?,
        val sig: String,
    )

    private val indexReassembler = PonderChunkHelper.Reassembler()
    private val bundleReassembler = PonderChunkHelper.Reassembler()

    @Volatile
    var index: PonderIndexPayload = PonderIndexPayload(emptyList(), emptyList())
        private set

    @Volatile
    private var pendingReturnScreen: Screen? = null

    fun onIndexChunk(sessionId: Int, chunkIndex: Int, lastChunk: Boolean, data: ByteArray) {
        val full = indexReassembler.accept(sessionId, chunkIndex, lastChunk, data) ?: return
        val payload = try {
            PonderIndexPayload.fromByteArray(full)
        } catch (_: Exception) {
            return
        }
        Minecraft.getInstance().execute {
            index = payload
        }
    }

    fun onOpenIndexPacket() {
        Minecraft.getInstance().execute {
            val mc = Minecraft.getInstance()
            mc.setScreen(HexPonderIndexScreen(mc.screen))
        }
    }

    fun openIndex(parent: Screen? = Minecraft.getInstance().screen) {
        Minecraft.getInstance().setScreen(HexPonderIndexScreen(parent))
    }

    fun openIndexWithPattern(
        pattern: HexPattern,
        actionId: ResourceLocation?,
        parent: Screen? = Minecraft.getInstance().screen,
    ) {
        val resolvedId = actionId ?: findActionId(pattern)
        val query = PatternSearchQuery(pattern, resolvedId, pattern.anglesSignature())
        Minecraft.getInstance().setScreen(HexPonderIndexScreen(parent, query))
    }

    fun openScene(sceneId: ResourceLocation, returnScreen: Screen? = Minecraft.getInstance().screen) {
        pendingReturnScreen = returnScreen
        MsgPonderLoadC2S(sceneId).sendToServer()
    }

    fun onBundleChunk(sessionId: Int, chunkIndex: Int, lastChunk: Boolean, data: ByteArray) {
        val full = bundleReassembler.accept(sessionId, chunkIndex, lastChunk, data) ?: return
        val bundle = try {
            PonderPlaybackBundle.fromByteArray(full)
        } catch (_: Exception) {
            return
        }
        Minecraft.getInstance().execute {
            val mc = Minecraft.getInstance()
            val cur = mc.screen
            if (cur is HexPonderScreen && cur.bundle.sceneId == bundle.sceneId) {
                cur.reloadBundle(bundle)
            } else {
                mc.setScreen(HexPonderScreen(bundle, pendingReturnScreen ?: cur))
            }
            pendingReturnScreen = null
        }
    }

    fun onSandboxResult(msg: MsgPonderSandboxExecS2C) {
        Minecraft.getInstance().execute {
            val screen = Minecraft.getInstance().screen
            if (screen is HexPonderScreen) {
                screen.onSandboxResult(msg)
            }
        }
    }

    /**
     * 安全查询图案对应的注册动作 ID（先匹配常规签名，再按坐标归一化匹配大法术 PER_WORLD_PATTERN，绝不触发 getHolder 污染）。
     */
    fun findActionId(pattern: HexPattern): ResourceLocation? {
        for ((key, entry) in HexActions.REGISTRY.entrySet()) {
            if (pattern.sigsEqual(entry.prototype())) {
                return key.location()
            }
        }

        val perWorldTag = HexActions.REGISTRY.getTag(HexTags.Actions.PER_WORLD_PATTERN).orElse(null)
            ?: return null
        val targetAngleCount = pattern.angles.size
        val targetRotations = HexDir.entries.map { dir ->
            HexPattern(dir, pattern.angles)
                .positions()
                .distinct()
                .sortedWith(compareBy<HexCoord>({ it.q }, { it.r }))
        }
        for (holder in perWorldTag) {
            val proto = holder.value().prototype()
            if (proto.angles.size != targetAngleCount) continue
            val candidate = proto.positions().distinct().sortedWith(compareBy<HexCoord>({ it.q }, { it.r }))
            if (matchesNormalizedCoords(targetRotations, candidate)) {
                val key = holder.unwrapKey().orElse(null) ?: continue
                return key.location()
            }
        }
        return null
    }

    private fun matchesNormalizedCoords(targets: List<List<HexCoord>>, candidate: List<HexCoord>): Boolean {
        if (candidate.size < 2) return false
        val o2 = candidate[1]
        for (target in targets) {
            if (target.size != candidate.size || target.size < 2) continue
            val o1 = target[1]
            var match = true
            for (i in target.indices) {
                val c1 = target[i]
                val c2 = candidate[i]
                if (c1.q - o1.q != c2.q - o2.q || c1.r - o1.r != c2.r - o2.r) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    fun categories(): List<PonderCategory> = index.categories

    fun entriesForCategory(categoryId: String?): List<PonderIndexEntry> {
        val all = index.entries
        return if (categoryId.isNullOrEmpty()) all else all.filter { it.category == categoryId }
    }

    fun clear() {
        indexReassembler.reset()
        bundleReassembler.reset()
        index = PonderIndexPayload(emptyList(), emptyList())
        pendingReturnScreen = null
    }
}
