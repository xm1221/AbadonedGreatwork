package cn.xm1221.abadoned_greatwork.ponder.data

import cn.xm1221.abadoned_greatwork.api.*
import at.petrak.hexcasting.common.lib.hex.HexActions
import com.google.gson.JsonElement
import io.netty.buffer.Unpooled
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * 思索目录分类定义（数据驱动：`data/<ns>/hex_ponder/categories/<id>.json`）。
 */
data class PonderCategory(
    val id: String,
    val title: String,
    val description: String,
    val iconItem: String,
    val sortOrder: Int = 0,
) {
    fun writeToBuf(buf: FriendlyByteBuf) {
        buf.writeUtf(id)
        buf.writeUtf(title)
        buf.writeUtf(description)
        buf.writeUtf(iconItem)
        buf.writeInt(sortOrder)
    }

    companion object {
        fun readFromBuf(buf: FriendlyByteBuf): PonderCategory = PonderCategory(
            id = buf.readUtf(),
            title = buf.readUtf(),
            description = buf.readUtf(),
            iconItem = buf.readUtf(),
            sortOrder = buf.readInt(),
        )
    }
}

/**
 * 场景触发条件（用于在物品 Tooltip、探知透镜、真理水晶中自动匹配并跳转对应思索场景）。
 */
data class PonderTriggers(
    val items: List<String> = emptyList(),
    val blocks: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val riddles: List<String> = emptyList(),
) {
    fun writeToBuf(buf: FriendlyByteBuf) {
        writeStringList(buf, items)
        writeStringList(buf, blocks)
        writeStringList(buf, actions)
        writeStringList(buf, riddles)
    }

    companion object {
        fun readFromBuf(buf: FriendlyByteBuf): PonderTriggers = PonderTriggers(
            items = readStringList(buf),
            blocks = readStringList(buf),
            actions = readStringList(buf),
            riddles = readStringList(buf),
        )
    }
}

/**
 * JSON 原始浮动提示气泡定义。
 */
data class PonderCalloutDef(
    val anchor: String = "canvas",
    val anchorIndex: Int = 0,
    val anchorQ: Int? = null,
    val anchorR: Int? = null,
    val accentColor: Int = 0xFFA875FF.toInt(),
    val text: String = "",
    val inlineIotas: List<String> = emptyList(),
    val pause: Boolean = false,
)

/**
 * JSON 原始知识点精讲卡片定义。
 */
data class PonderKnowledgeCardDef(
    val title: String = "",
    val body: String = "",
    val inlineIotas: List<String> = emptyList(),
)

/**
 * JSON 原始步骤定义。
 */
data class PonderStepDef(
    val type: String = "execute",
    val action: String = "",
    val pattern: String = "",
    val startDir: String = "NORTH_EAST",
    val originQ: Int = -1,
    val originR: Int = 2,
    val color: Int = -1,
    val cursorItem: String? = null,
    val strokeTicks: Int = 14,
    val holdTicks: Int = 40,
    val title: String = "",
    val chapter: String = "",
    val pushRaw: JsonElement? = null,
    val peekIndex: Int = -1,
    val peekIndices: List<Int> = emptyList(),
    val callout: PonderCalloutDef? = null,
    val clearBeforeOverride: Boolean? = null,
)

/**
 * 单条数据驱动解锁条件定义。
 */
data class PonderUnlockCondition(
    val type: String = "manual",
    val id: String = "",
)

/**
 * 场景锁定与数据驱动解锁规则。
 */
data class PonderUnlockDef(
    val locked: Boolean = false,
    val mode: String = "all",
    val hint: String = "",
    val conditions: List<PonderUnlockCondition> = emptyList(),
)

/**
 * 完整的数据驱动思索场景定义（由数据包加载并保存在服务端）。
 */
data class PonderSceneDef(
    val id: ResourceLocation,
    val title: String,
    val subtitle: String,
    val category: String,
    val sortOrder: Int,
    val iconItem: String,
    val cursorItem: String,
    val requiresMod: String,
    val hexSize: Float,
    val defaultInterval: Int,
    val defaultStrokeTicks: Int,
    val clearBefore: Boolean,
    val startDir: String,
    val escapedColor: Int,
    val triggers: PonderTriggers,
    val unlock: PonderUnlockDef = PonderUnlockDef(),
    val knowledgeCards: List<PonderKnowledgeCardDef>,
    val steps: List<PonderStepDef>,
) {
    fun toIndexEntry(): PonderIndexEntry {
        val actionSet = LinkedHashSet<String>()
        triggers.actions.filter { it.isNotBlank() }.forEach { actionSet.add(it) }
        for (st in steps) {
            if (st.action.isNotBlank()) actionSet.add(st.action)
        }

        val sigSet = LinkedHashSet<String>()
        for (st in steps) {
            if (st.pattern.isNotBlank()) sigSet.add(st.pattern)
        }
        for (actId in actionSet) {
            val rl = ResourceLocation.tryParse(actId) ?: continue
            runCatching { HexActions.REGISTRY.get(rl)?.prototype()?.anglesSignature() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { sigSet.add(it) }
        }

        val termSet = LinkedHashSet<String>()
        for (riddle in triggers.riddles) {
            if (riddle.isNotBlank()) termSet.add(riddle)
        }
        for (card in knowledgeCards) {
            if (card.title.isNotBlank()) termSet.add(card.title)
            if (card.body.isNotBlank()) termSet.add(card.body)
        }
        for (st in steps) {
            if (st.chapter.isNotBlank()) termSet.add(st.chapter)
            if (st.title.isNotBlank()) termSet.add(st.title)
        }

        return PonderIndexEntry(
            id = id,
            title = title,
            subtitle = subtitle,
            category = category,
            sortOrder = sortOrder,
            iconItem = iconItem,
            stepCount = steps.size,
            triggers = triggers,
            searchTerms = termSet.toList(),
            actionIds = actionSet.toList(),
            patternSigs = sigSet.toList(),
            locked = unlock.locked,
            lockHint = unlock.hint,
        )
    }
}

/**
 * 同步给客户端的轻量场景索引条目（不含全部预模拟帧，进服同步开销极小）。
 */
data class PonderIndexEntry(
    val id: ResourceLocation,
    val title: String,
    val subtitle: String,
    val category: String,
    val sortOrder: Int,
    val iconItem: String,
    val stepCount: Int,
    val triggers: PonderTriggers,
    val searchTerms: List<String> = emptyList(),
    val actionIds: List<String> = emptyList(),
    val patternSigs: List<String> = emptyList(),
    val locked: Boolean = false,
    val lockHint: String = "",
) {
    fun writeToBuf(buf: FriendlyByteBuf) {
        buf.writeResourceLocation(id)
        buf.writeUtf(title)
        buf.writeUtf(subtitle)
        buf.writeUtf(category)
        buf.writeInt(sortOrder)
        buf.writeUtf(iconItem)
        buf.writeInt(stepCount)
        triggers.writeToBuf(buf)
        writeStringList(buf, searchTerms)
        writeStringList(buf, actionIds)
        writeStringList(buf, patternSigs)
        buf.writeBoolean(locked)
        buf.writeUtf(lockHint)
    }

    companion object {
        fun readFromBuf(buf: FriendlyByteBuf): PonderIndexEntry = PonderIndexEntry(
            id = buf.readResourceLocation(),
            title = buf.readUtf(),
            subtitle = buf.readUtf(),
            category = buf.readUtf(),
            sortOrder = buf.readInt(),
            iconItem = buf.readUtf(),
            stepCount = buf.readInt(),
            triggers = PonderTriggers.readFromBuf(buf),
            searchTerms = readStringList(buf),
            actionIds = readStringList(buf),
            patternSigs = readStringList(buf),
            locked = buf.readBoolean(),
            lockHint = buf.readUtf(),
        )
    }
}

/**
 * 完整思索目录与触发器索引负载。
 */
data class PonderIndexPayload(
    val categories: List<PonderCategory>,
    val entries: List<PonderIndexEntry>,
) {
    fun toByteArray(): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeInt(categories.size)
        for (cat in categories) cat.writeToBuf(buf)
        buf.writeInt(entries.size)
        for (entry in entries) entry.writeToBuf(buf)
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        buf.release()
        return bytes
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): PonderIndexPayload {
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
            val catCount = buf.readInt()
            val cats = ArrayList<PonderCategory>(catCount)
            repeat(catCount) { cats.add(PonderCategory.readFromBuf(buf)) }
            val entryCount = buf.readInt()
            val entries = ArrayList<PonderIndexEntry>(entryCount)
            repeat(entryCount) { entries.add(PonderIndexEntry.readFromBuf(buf)) }
            buf.release()
            return PonderIndexPayload(cats, entries)
        }
    }
}

/**
 * 服务端解析后的浮动提示气泡（内联 Iota 已在服务端编码为客户端安全 Tag）。
 */
data class ResolvedCallout(
    val anchor: String,
    val anchorIndex: Int,
    val anchorQ: Int?,
    val anchorR: Int?,
    val accentColor: Int,
    val text: String,
    val inlineIotaTags: List<CompoundTag>,
    val pause: Boolean,
) {
    fun writeToBuf(buf: FriendlyByteBuf) {
        buf.writeUtf(anchor)
        buf.writeInt(anchorIndex)
        buf.writeBoolean(anchorQ != null)
        if (anchorQ != null) buf.writeInt(anchorQ)
        buf.writeBoolean(anchorR != null)
        if (anchorR != null) buf.writeInt(anchorR)
        buf.writeInt(accentColor)
        buf.writeUtf(text)
        writeTagList(buf, inlineIotaTags)
        buf.writeBoolean(pause)
    }

    companion object {
        fun readFromBuf(buf: FriendlyByteBuf): ResolvedCallout = ResolvedCallout(
            anchor = buf.readUtf(),
            anchorIndex = buf.readInt(),
            anchorQ = if (buf.readBoolean()) buf.readInt() else null,
            anchorR = if (buf.readBoolean()) buf.readInt() else null,
            accentColor = buf.readInt(),
            text = buf.readUtf(),
            inlineIotaTags = readTagList(buf),
            pause = buf.readBoolean(),
        )
    }
}

/**
 * 服务端解析后的知识点卡片。
 */
data class ResolvedKnowledgeCard(
    val title: String,
    val body: String,
    val inlineIotaTags: List<CompoundTag>,
) {
    fun writeToBuf(buf: FriendlyByteBuf) {
        buf.writeUtf(title)
        buf.writeUtf(body)
        writeTagList(buf, inlineIotaTags)
    }

    companion object {
        fun readFromBuf(buf: FriendlyByteBuf): ResolvedKnowledgeCard = ResolvedKnowledgeCard(
            title = buf.readUtf(),
            body = buf.readUtf(),
            inlineIotaTags = readTagList(buf),
        )
    }
}

/**
 * 单步预模拟快照：仅含客户端安全的 NBT Tag 与格式化组件，支持 O(1) 任意前后跳转。
 */
data class StepSnapshot(
    val stepIndex: Int,
    val stepType: String,
    val actionId: String,
    val title: String,
    val chapter: String,
    val displaySig: String,
    val displayStartDir: String,
    val hasDisplayPattern: Boolean,
    val originQ: Int,
    val originR: Int,
    val clearCanvasBefore: Boolean,
    val patternColor: Int,
    val cursorItemId: String,
    val strokeTicks: Int,
    val holdTicks: Int,
    val stackTags: List<CompoundTag>,
    val poppedCount: Int,
    val pushedCount: Int,
    val openParens: Int,
    val parenthesizedTags: List<CompoundTag>,
    val escapeNext: Boolean,
    val ravenmindTag: CompoundTag?,
    val opsConsumed: Long,
    val imageNbt: CompoundTag,
    val resolutionType: String,
    val consoleLines: List<Component>,
    val mishapText: Component?,
    val callout: ResolvedCallout?,
) {
    fun writeToBuf(buf: FriendlyByteBuf) {
        buf.writeInt(stepIndex)
        buf.writeUtf(stepType)
        buf.writeUtf(actionId)
        buf.writeUtf(title)
        buf.writeUtf(chapter)
        buf.writeUtf(displaySig)
        buf.writeUtf(displayStartDir)
        buf.writeBoolean(hasDisplayPattern)
        buf.writeInt(originQ)
        buf.writeInt(originR)
        buf.writeBoolean(clearCanvasBefore)
        buf.writeInt(patternColor)
        buf.writeUtf(cursorItemId)
        buf.writeInt(strokeTicks)
        buf.writeInt(holdTicks)
        writeTagList(buf, stackTags)
        buf.writeInt(poppedCount)
        buf.writeInt(pushedCount)
        buf.writeInt(openParens)
        writeTagList(buf, parenthesizedTags)
        buf.writeBoolean(escapeNext)
        writeNullableTag(buf, ravenmindTag)
        buf.writeLong(opsConsumed)
        buf.writeNbt(imageNbt)
        buf.writeUtf(resolutionType)
        writeComponentList(buf, consoleLines)
        buf.writeBoolean(mishapText != null)
        if (mishapText != null) cn.xm1221.abadoned_greatwork.api.writeComponent(buf, mishapText)
        buf.writeBoolean(callout != null)
        callout?.writeToBuf(buf)
    }

    companion object {
        fun readFromBuf(buf: FriendlyByteBuf): StepSnapshot = StepSnapshot(
            stepIndex = buf.readInt(),
            stepType = buf.readUtf(),
            actionId = buf.readUtf(),
            title = buf.readUtf(),
            chapter = buf.readUtf(),
            displaySig = buf.readUtf(),
            displayStartDir = buf.readUtf(),
            hasDisplayPattern = buf.readBoolean(),
            originQ = buf.readInt(),
            originR = buf.readInt(),
            clearCanvasBefore = buf.readBoolean(),
            patternColor = buf.readInt(),
            cursorItemId = buf.readUtf(),
            strokeTicks = buf.readInt(),
            holdTicks = buf.readInt(),
            stackTags = readTagList(buf),
            poppedCount = buf.readInt(),
            pushedCount = buf.readInt(),
            openParens = buf.readInt(),
            parenthesizedTags = readTagList(buf),
            escapeNext = buf.readBoolean(),
            ravenmindTag = readNullableTag(buf),
            opsConsumed = buf.readLong(),
            imageNbt = buf.readNbt() ?: CompoundTag(),
            resolutionType = buf.readUtf(),
            consoleLines = readComponentList(buf),
            mishapText = if (buf.readBoolean()) cn.xm1221.abadoned_greatwork.api.readComponent(buf) else null,
            callout = if (buf.readBoolean()) ResolvedCallout.readFromBuf(buf) else null,
        )
    }
}

/**
 * 完整场景播放数据包（服务端一次性预模拟生成，按字节分块下发客户端）。
 */
data class PonderPlaybackBundle(
    val sceneId: ResourceLocation,
    val title: String,
    val subtitle: String,
    val iconItemId: String,
    val hexSize: Float,
    val escapedColor: Int,
    val defaultCursorItemId: String,
    val knowledgeCards: List<ResolvedKnowledgeCard>,
    val snapshots: List<StepSnapshot>,
) {
    fun toByteArray(): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeResourceLocation(sceneId)
        buf.writeUtf(title)
        buf.writeUtf(subtitle)
        buf.writeUtf(iconItemId)
        buf.writeFloat(hexSize)
        buf.writeInt(escapedColor)
        buf.writeUtf(defaultCursorItemId)
        buf.writeInt(knowledgeCards.size)
        for (card in knowledgeCards) card.writeToBuf(buf)
        buf.writeInt(snapshots.size)
        for (snap in snapshots) snap.writeToBuf(buf)
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        buf.release()
        return bytes
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): PonderPlaybackBundle {
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
            val sceneId = buf.readResourceLocation()
            val title = buf.readUtf()
            val subtitle = buf.readUtf()
            val iconItemId = buf.readUtf()
            val hexSize = buf.readFloat()
            val escapedColor = buf.readInt()
            val defaultCursorItemId = buf.readUtf()
            val cardCount = buf.readInt()
            val cards = ArrayList<ResolvedKnowledgeCard>(cardCount)
            repeat(cardCount) { cards.add(ResolvedKnowledgeCard.readFromBuf(buf)) }
            val snapCount = buf.readInt()
            val snaps = ArrayList<StepSnapshot>(snapCount)
            repeat(snapCount) { snaps.add(StepSnapshot.readFromBuf(buf)) }
            buf.release()
            return PonderPlaybackBundle(
                sceneId = sceneId,
                title = title,
                subtitle = subtitle,
                iconItemId = iconItemId,
                hexSize = hexSize,
                escapedColor = escapedColor,
                defaultCursorItemId = defaultCursorItemId,
                knowledgeCards = cards,
                snapshots = snaps,
            )
        }
    }
}

internal fun writeStringList(buf: FriendlyByteBuf, list: List<String>) {
    buf.writeInt(list.size)
    for (s in list) buf.writeUtf(s)
}

internal fun readStringList(buf: FriendlyByteBuf): List<String> {
    val n = buf.readInt()
    val out = ArrayList<String>(n)
    repeat(n) { out.add(buf.readUtf()) }
    return out
}

internal fun writeTagList(buf: FriendlyByteBuf, list: List<CompoundTag>) {
    buf.writeInt(list.size)
    for (tag in list) buf.writeNbt(tag)
}

internal fun readTagList(buf: FriendlyByteBuf): List<CompoundTag> {
    val n = buf.readInt()
    val out = ArrayList<CompoundTag>(n)
    repeat(n) { out.add(buf.readNbt() ?: CompoundTag()) }
    return out
}

internal fun writeNullableTag(buf: FriendlyByteBuf, tag: CompoundTag?) {
    buf.writeBoolean(tag != null)
    if (tag != null) buf.writeNbt(tag)
}

internal fun readNullableTag(buf: FriendlyByteBuf): CompoundTag? {
    if (!buf.readBoolean()) return null
    return buf.readNbt()
}

internal fun writeComponentList(buf: FriendlyByteBuf, list: List<Component>) {
    buf.writeInt(list.size)
    for (comp in list) cn.xm1221.abadoned_greatwork.api.writeComponent(buf, comp)
}

internal fun readComponentList(buf: FriendlyByteBuf): List<Component> {
    val n = buf.readInt()
    val out = ArrayList<Component>(n)
    repeat(n) { out.add(cn.xm1221.abadoned_greatwork.api.readComponent(buf)) }
    return out
}
