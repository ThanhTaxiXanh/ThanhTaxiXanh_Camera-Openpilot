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
