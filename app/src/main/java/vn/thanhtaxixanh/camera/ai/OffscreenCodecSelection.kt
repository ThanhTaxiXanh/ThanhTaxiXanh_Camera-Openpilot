package vn.thanhtaxixanh.camera.ai

/**
 * Prefer a software-only AVC decoder for the OPTIONAL A500S YUV sidecar.
 *
 * The primary Direct Surface preview already owns a hardware AVC decoder.
 * Some phones cannot run a second hardware decoder concurrently. Native
 * codec availability is probed on the sidecar worker (never the RTP thread).
 *
 * Candidate availability does NOT establish successful physical YUV output.
 */
object OffscreenCodecSelection {
    /** Owner-controlled mode for the OPTIONAL decoder, never the video Surface. */
    enum class Preference { SOFTWARE_FIRST, PLATFORM_DEFAULT }

    fun shouldProbeSoftware(preference: Preference): Boolean =
        preference == Preference.SOFTWARE_FIRST

    data class Candidate(
        val name: String,
        val supportsAvc: Boolean,
        val softwareOnly: Boolean
    )

    fun softwareAvcCandidates(input: List<Candidate>): List<String> =
        input.asSequence()
            .filter { it.softwareOnly && it.supportsAvc && it.name.isNotBlank() }
            .map { it.name }
            .distinct()
            .toList()

    enum class InputWaitVerdict { RETRY_DRAIN, RESYNC_IDR }

    data class InputWaitPlan(val maxAttempts: Int, val timeoutUs: Long) {
        init {
            require(maxAttempts in 1..8)
            require(timeoutUs in 1_000L..25_000L)
            // Strictly bounded: even the slow software decoder must not
            // monopolize its own worker indefinitely on a stale AU.
            require(maxAttempts * timeoutUs <= 200_000L)
        }
    }

    /**
     * Real owner video V1.3.37 showed c2.android.avc.decoder [SOFTWARE]:
     * 7 input exhaustion events and 13 resets by 28 seconds, even while
     * the main RTSP Surface played. Repeated 10ms input polling did not
     * allow sufficient time for software AVC to free native buffers.
     *
     * This is a bounded mitigation, NOT a device-proven throughput fix.
     * The independent age/epoch gate still discards stale compressed AUs.
     */
    fun inputWaitPlan(softwareDecoder: Boolean): InputWaitPlan =
        if (softwareDecoder) InputWaitPlan(maxAttempts=8, timeoutUs=20_000L)
        else InputWaitPlan(maxAttempts=3, timeoutUs=10_000L)

    /**
     * A single transient -1 from dequeueInputBuffer is not a codec failure.
     * Bounded retries must drain outputs before reattempting input; after
     * exhausting the budget, reset ONLY the secondary GOP/decoder.
     */
    fun inputBufferUnavailable(
        attempts: Int,
        maxAttempts: Int = 3
    ): InputWaitVerdict {
        require(maxAttempts in 1..8)
        require(attempts in 1..maxAttempts)
        return if (attempts < maxAttempts) InputWaitVerdict.RETRY_DRAIN
        else InputWaitVerdict.RESYNC_IDR
    }
}
