package vn.thanhtaxixanh.camera.ai

/**
 * Passive advisory decision engine. Never issues vehicle control commands.
 *
 * Every warning requires a real, fresh, ground-truth-calibrated inference
 * result, trusted GPS speed, two consecutive bad samples, and a cool-down.
 * Sound/vibration are deliberately not attached until real-world validation.
 */
class AdasWarningEngine(
    private val minimumSpeedKmh: Float = 20f,
    private val headwayLimitSeconds: Float = 2f,
    private val collisionTtcSeconds: Float = 3f,
    private val debounceSamples: Int = 2,
    private val cooldownMillis: Long = 5_000L
) {
    enum class Kind { NONE, HEADWAY, COLLISION_RISK }

    data class Warning(val kind: Kind, val detail: String, val atMs: Long)

    private var candidate = Kind.NONE
    private var candidateSamples = 0
    private var lastWarningAt = Long.MIN_VALUE
    private var lastSampleTime = Long.MIN_VALUE

    init {
        require(minimumSpeedKmh in 0f..150f)
        require(headwayLimitSeconds in 0.5f..6f)
        require(collisionTtcSeconds in 0.5f..8f)
        require(debounceSamples in 1..10)
        require(cooldownMillis in 0L..60_000L)
    }

    fun reset() {
        candidate = Kind.NONE
        candidateSamples = 0
        lastSampleTime = Long.MIN_VALUE
        lastWarningAt = Long.MIN_VALUE
    }

    fun evaluate(
        nowElapsedMs: Long,
        state: VisionSafetyGate.VisibleState,
        gpsSpeedKmh: Float?
    ): Warning? {
        val speedReady = gpsSpeedKmh != null && gpsSpeedKmh.isFinite() &&
            gpsSpeedKmh >= minimumSpeedKmh
        val isNewSample = lastSampleTime == Long.MIN_VALUE ||
            nowElapsedMs > lastSampleTime
        val sampleFresh = lastSampleTime == Long.MIN_VALUE ||
            nowElapsedMs - lastSampleTime <= 1000L

        if (!state.mayWarn || !speedReady || !isNewSample || !sampleFresh) {
            candidate = Kind.NONE
            candidateSamples = 0
            lastSampleTime = nowElapsedMs
            return null
        }

        val c = when {
            state.timeToCollisionSeconds?.let {
                it.isFinite() && it in 0f..<collisionTtcSeconds
            } == true -> Kind.COLLISION_RISK
            state.headwaySeconds?.let {
                it.isFinite() && it in 0f..<headwayLimitSeconds
            } == true -> Kind.HEADWAY
            else -> Kind.NONE
        }
        lastSampleTime = nowElapsedMs
        if (c == Kind.NONE) {
            candidate = Kind.NONE
            candidateSamples = 0
            return null
        }
        if (c == candidate) candidateSamples++ else {
            candidate = c
            candidateSamples = 1
        }
        if (candidateSamples < debounceSamples) return null
        if (lastWarningAt != Long.MIN_VALUE &&
            nowElapsedMs - lastWarningAt < cooldownMillis) return null
        lastWarningAt = nowElapsedMs
        return Warning(c, when(c) {
            Kind.HEADWAY -> "Khoảng cách theo thời gian dưới ngưỡng đã hiệu chuẩn"
            Kind.COLLISION_RISK -> "TTC ước lượng thấp – CẦN ĐÁNH GIÁ"
            Kind.NONE -> ""
        }, nowElapsedMs)
    }
}
