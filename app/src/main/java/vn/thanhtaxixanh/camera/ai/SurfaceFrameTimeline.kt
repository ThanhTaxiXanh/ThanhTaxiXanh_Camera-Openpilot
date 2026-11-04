package vn.thanhtaxixanh.camera.ai

import kotlin.math.ceil

/**
 * Pure local nanos correlation: releaseOutputBuffer(render=true) ->
 * MediaCodec.OnFrameRenderedListener callback render nanoTime.
 *
 * Render callback scheduling does not necessarily identify optical
 * scanout, SurfaceFlinger composition or the actual camera scene time.
 * Treat all values as non-safety diagnostic evidence only.
 */
class SurfaceFrameTimeline(
    private val maxPending: Int = 96,
    private val sampleWindow: Int = 128
) {
    init {
        require(maxPending in 8..512)
        require(sampleWindow in 8..512)
    }
    data class Snapshot(
        val matched: Long,
        val unmatched: Long,
        val evicted: Long,
        val invalidClock: Long,
        val pending: Int,
        val lastReleaseToRenderMs: Double?,
        val p95ReleaseToRenderMs: Double?,
        val maxReleaseToRenderMs: Double?
    )
    private val releases = LinkedHashMap<Long, Long>()
    private val durations = ArrayDeque<Double>()
    private var matched = 0L
    private var unmatched = 0L
    private var evicted = 0L
    private var invalidClock = 0L

    @Synchronized fun released(ptsUs: Long, releasedAtNs: Long) {
        if (ptsUs < 0L || releasedAtNs < 0L) {
            invalidClock++
            return
        }
        if (releases.put(ptsUs,releasedAtNs) != null) evicted++
        while (releases.size > maxPending) {
            releases.remove(releases.keys.first())
            evicted++
        }
    }

    @Synchronized fun forget(ptsUs: Long) {
        releases.remove(ptsUs)
    }

    @Synchronized fun rendered(ptsUs: Long, renderAtNs: Long): Double? {
        val releasedAtNs = releases.remove(ptsUs)
        if (releasedAtNs == null) {
            unmatched++
            return null
        }
        if (renderAtNs < releasedAtNs || renderAtNs < 0L) {
            invalidClock++
            return null
        }
        val millis = (renderAtNs-releasedAtNs).toDouble()/1_000_000.0
        durations.addLast(millis)
        if (durations.size > sampleWindow) durations.removeFirst()
        matched++
        return millis
    }

    @Synchronized fun snapshot(): Snapshot {
        val sorted = durations.sorted()
        val p95 = if (sorted.isEmpty()) null else
            sorted[(ceil(.95*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)]
        return Snapshot(matched,unmatched,evicted,invalidClock,releases.size,
            durations.lastOrNull(),p95,sorted.lastOrNull())
    }

    @Synchronized fun clear() {
        releases.clear()
        durations.clear()
    }
}
