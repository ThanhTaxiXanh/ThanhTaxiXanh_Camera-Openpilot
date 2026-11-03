package vn.thanhtaxixanh.camera.ai

/**
 * Small bounded reorder window for UDP RTP packets.
 *
 * RTP/AVP/UDP may deliver out-of-order packets. Forwarding packet #N+2 ahead
 * of #N+1 directly into an H264 FU-A assembler invalidates the entire AU.
 * This class gives a gap at most maxDelayMs to close; after that, forward
 * progress is preferred over indefinite blocking. No codec thread is used.
 *
 * One UDP receiver thread owns this object.
 */
class RtpJitterQueue(
    private val capacity: Int = 32,
    private val maxDelayMs: Long = 55L
) {
    private data class Stored(val bytes: ByteArray, val receivedAtMs: Long)
    private val pending = HashMap<Int, Stored>()
    private var nextSequence: Int? = null

    var outOfOrderPackets: Long = 0L
        private set
    var recoveredOutOfOrderPackets: Long = 0L
        private set
    var oldOrDuplicatePackets: Long = 0L
        private set
    var skippedMissingPackets: Long = 0L
        private set

    init {
        require(capacity in 4..256)
        require(maxDelayMs in 10L..500L)
    }

    fun accept(data: ByteArray, timeMs: Long, emit: (ByteArray) -> Unit) {
        if (data.size < 12 || (data[0].toInt() and 0xc0) != 0x80) {
            emit(data)
            return
        }
        val seq = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        if (nextSequence == null) {
            nextSequence = (seq + 1) and 65535
            emit(data)
            return
        }
        val expected = nextSequence!!
        val forward = (seq - expected + 65536) and 65535
        when {
            forward == 0 -> {
                nextSequence = (expected + 1) and 65535
                emit(data)
                flushContiguous(emit)
            }
            forward in 1..32767 -> {
                if (!pending.containsKey(seq)) {
                    pending[seq] = Stored(data, timeMs)
                    outOfOrderPackets++
                } else oldOrDuplicatePackets++
                // Skip a gap if the reorder buffer is full, or if the source
                // has leapt too far for this window to ever recover.
                if (pending.size >= capacity || forward > capacity)
                    releaseOldestGap(emit)
            }
            else -> oldOrDuplicatePackets++
        }
        tick(timeMs, emit)
    }

    fun tick(timeMs: Long, emit: (ByteArray) -> Unit) {
        if (pending.isNotEmpty() &&
            pending.values.any { timeMs - it.receivedAtMs >= maxDelayMs }) {
            releaseOldestGap(emit)
        }
    }

    private fun flushContiguous(emit: (ByteArray) -> Unit) {
        while (true) {
            val seq = nextSequence ?: break
            val entry = pending.remove(seq) ?: break
            recoveredOutOfOrderPackets++
            nextSequence = (seq + 1) and 65535
            emit(entry.bytes)
        }
    }

    private fun releaseOldestGap(emit: (ByteArray) -> Unit) {
        val current = nextSequence ?: return
        val firstSeq = pending.keys.minByOrNull { (it - current + 65536) and 65535 }
            ?: return
        val skip = (firstSeq - current + 65536) and 65535
        if (skip > 0) skippedMissingPackets += skip
        nextSequence = (firstSeq + 1) and 65535
        val packet = pending.remove(firstSeq) ?: return
        emit(packet.bytes)
        flushContiguous(emit)
    }
}
