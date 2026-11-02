package vn.thanhtaxixanh.camera.ai

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * RFC 2326 §10.12 RTSP/TCP wire demultiplexer.
 * A single socket can carry text RTSP replies and binary '$'-framed RTP/RTCP.
 * Neither packets nor RTSP messages are assumed to align with TCP read() calls.
 *
 * This class is intentionally pure JVM code for deterministic unit testing.
 */
class RtspWireReader(private val input: InputStream) {
    sealed class Event {
        data class Reply(
            val code: Int,
            val headers: Map<String, String>,
            val body: ByteArray
        ) : Event() {
            fun header(name: String): String = headers[name.lowercase()].orEmpty()
            val text: String get() = String(body, Charsets.US_ASCII)
        }

        data class Interleaved(val channel: Int, val payload: ByteArray) : Event()
    }

    private fun readByte(): Int = input.read().also {
        if (it < 0) throw EOFException("RTSP TCP closed")
    }

    private fun readFully(size: Int): ByteArray {
        require(size in 0..65536) { "Invalid RTSP/RTP frame length $size" }
        val result = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = input.read(result, at, size - at)
            if (n < 0) throw EOFException("Truncated RTSP/RTP body ($at/$size)")
            if (n == 0) {
                result[at] = readByte().toByte()
                at++
            } else at += n
        }
        return result
    }

    private fun readLine(startByte: Int = -1): String {
        val buffer = ByteArrayOutputStream(96)
        if (startByte >= 0) buffer.write(startByte)
        while (true) {
            val b = readByte()
            if (b == 10) break
            if (b != 13) buffer.write(b)
            require(buffer.size() < 8192) { "RTSP header line exceeds 8192 bytes" }
        }
        return buffer.toString(Charsets.US_ASCII.name())
    }

    /** Reads one framed interleaved packet OR one complete RTSP reply. */
    fun next(): Event {
        var first = readByte()
        while (first == 10 || first == 13) first = readByte()
        if (first == 0x24) {
            val channel = readByte()
            val size = (readByte() shl 8) or readByte()
            return Event.Interleaved(channel, readFully(size))
        }
        if (first != 'R'.code) {
            // Never print raw binary payload into the UI as replacement characters.
            throw IllegalStateException("Unknown RTSP prefix 0x" +
                first.toString(16).padStart(2, '0').uppercase() +
                " (not RTSP text or interleaved '$')")
        }

        val status = readLine(first)
        require(status.startsWith("RTSP/1.")) {
            "Unexpected RTSP status: " + status.take(100)
        }
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IllegalStateException("RTSP status lacks numeric code")
        val headers = LinkedHashMap<String, String>()
        var terminated = false
        repeat(100) {
            if (!terminated) {
                val line = readLine()
                if (line.isEmpty()) terminated = true
                else {
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
        }
        require(terminated) { "RTSP response contains too many headers" }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..65536) { "Invalid RTSP Content-Length" }
        return Event.Reply(code, headers, readFully(length))
    }

    /**
     * While waiting for a response, consume binary RTP packets arriving ahead
     * of it (notably immediately around PLAY). A capped callback may stash them.
     * A pending/old CSeq response is never silently mistaken for the new one.
     */
    fun awaitResponse(
        expectedCseq: Int,
        onInterleaved: (Event.Interleaved) -> Unit = {}
    ): Event.Reply {
        var skipped = 0
        var mediaBytes = 0
        try {
            while (skipped < 128 && mediaBytes < 512_000) {
                when (val item = next()) {
                    is Event.Interleaved -> {
                        skipped++
                        mediaBytes += item.payload.size
                        onInterleaved(item)
                    }
                    is Event.Reply -> {
                        val cseq = item.header("cseq").toIntOrNull()
                        if (cseq != null && cseq != expectedCseq) {
                            throw IllegalStateException("RTSP CSeq mismatch: " +
                                "expected=$expectedCseq received=$cseq")
                        }
                        return item
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException(
                "RTSP CSeq $expectedCseq timeout; " +
                    "skipped $skipped binary blocks / $mediaBytes bytes", e
            )
        }
        throw IllegalStateException(
            "RTSP CSeq $expectedCseq: too much interleaved data " +
                "($skipped blocks/$mediaBytes bytes), no RTSP response"
        )
    }
}
