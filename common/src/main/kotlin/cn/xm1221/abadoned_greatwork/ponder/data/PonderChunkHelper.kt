package cn.xm1221.abadoned_greatwork.ponder.data

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 字节分块收发辅助工具：防止大帧快照或长场景索引超过单包上限（按 ≤18,000B 切片发送，客户端累齐尾包后组装）。
 */
object PonderChunkHelper {
    @PublishedApi
    internal const val CHUNK_SIZE = 18_000
    private val NEXT_SESSION = AtomicInteger(1)

    fun nextSessionId(): Int = NEXT_SESSION.getAndIncrement()

    inline fun sendChunks(
        bytes: ByteArray,
        crossinline sender: (sessionId: Int, chunkIndex: Int, lastChunk: Boolean, slice: ByteArray) -> Unit,
    ) {
        val sessionId = nextSessionId()
        if (bytes.isEmpty()) {
            sender(sessionId, 0, true, ByteArray(0))
            return
        }
        val totalChunks = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        for (idx in 0 until totalChunks) {
            val from = idx * CHUNK_SIZE
            val to = minOf(bytes.size, from + CHUNK_SIZE)
            val slice = bytes.copyOfRange(from, to)
            sender(sessionId, idx, idx == totalChunks - 1, slice)
        }
    }

    /**
     * 客户端分块重组缓存器（仅在主渲染线程访问）。
     */
    class Reassembler {
        private var currentSession = -1
        private var expectedChunk = 0
        private var buffer = ByteArrayOutputStream()

        fun accept(sessionId: Int, chunkIndex: Int, lastChunk: Boolean, data: ByteArray): ByteArray? {
            if (chunkIndex == 0 || sessionId != currentSession) {
                currentSession = sessionId
                expectedChunk = 0
                buffer = ByteArrayOutputStream()
            }
            if (chunkIndex != expectedChunk) {
                // 失序或丢包则重置会话
                currentSession = -1
                return null
            }
            buffer.write(data)
            expectedChunk++
            if (!lastChunk) return null
            currentSession = -1
            return buffer.toByteArray()
        }

        fun reset() {
            currentSession = -1
            expectedChunk = 0
            buffer = ByteArrayOutputStream()
        }
    }
}
