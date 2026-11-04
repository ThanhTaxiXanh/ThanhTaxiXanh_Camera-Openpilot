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
    // A late packet from a retired RTP epoch must not switch the queue BACK.
    // Remember only a small, bounded number of old identities, for 256
    // observed packets (not a wall-clock or camera exposure measurement).
    private data class Identity(val ssrc: Long, val payloadType: Int)
    private data class Retired(val id: Identity, val expiresAfter: Long)
    private val retired = ArrayDeque<Retired>()
    private var acceptedPacketOrdinal = 0L
    private var activeIdentity: Identity? = null
    private val retiredHoldPackets = 256L

    @Volatile var lateEpochPackets: Long = 0L
        private set

    @Volatile var sourceEpochResets: Long = 0L
        private set

    @Volatile
    var outOfOrderPackets: Long = 0L
        private set
    @Volatile
    var recoveredOutOfOrderPackets: Long = 0L
        private set
    @Volatile
    var oldOrDuplicatePackets: Long = 0L
        private set
    @Volatile
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
        acceptedPacketOrdinal++
        while (retired.isNotEmpty() &&
            retired.first().expiresAfter < acceptedPacketOrdinal)
            retired.removeFirst()
        val source = Identity(RtpReceiverDiagnostics.unsigned32(data,8),
            data[1].toInt() and 0x7f)
        val current = activeIdentity
        if (current != null && current != source) {
            // Ignore packets that arrive late from a previous stream epoch.
            // A former SSRC/PT is allowed again after a bounded quarantine;
            // this is not an assumption about the camera's source identity.
            if (retired.any { it.id == source }) {
                lateEpochPackets++
                oldOrDuplicatePackets++
                return
            }
            retired.addLast(Retired(current,
                acceptedPacketOrdinal + retiredHoldPackets))
            while (retired.size > 4) retired.removeFirst()
            // Never hold UDP packets from two RTP sequence domains together.
            // The downstream H264 assembler will reject non-IDR frames
            // after the new SSRC/PT appears.
            pending.clear()
            nextSequence = null
            sourceEpochResets++
        }
        activeIdentity = source
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
        // More than one independent sequence gap can expire while the UDP
        // socket is quiet. Drain EVERY expired gap in one bounded tick,
        // rather than leaving older packets for another socket timeout.
        var released = 0
        while (pending.isNotEmpty() && released < capacity &&
            pending.values.any {
                timeMs >= it.receivedAtMs &&
                    timeMs - it.receivedAtMs >= maxDelayMs
            }) {
            releaseOldestGap(emit)
            released++
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
