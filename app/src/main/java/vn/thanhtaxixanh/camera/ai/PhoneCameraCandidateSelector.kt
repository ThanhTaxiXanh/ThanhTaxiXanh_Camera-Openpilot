package vn.thanhtaxixanh.camera.ai

/**
 * Physical Camera2 source selection for a real 640x480 YUV_420_888 stream.
 *
 * Different camera IDs (including logical/auxiliary rear modules) expose
 * different StreamConfigurationMaps. Selecting the FIRST rear camera
 * without checking its YUV format can incorrectly reject a handset that
 * has another usable rear Camera2 stream.
 *
 * Prefer a compatible rear camera, then front, then other. Within each
 * facing class prefer SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME, which permits
 * the existing RAW/ONNX age gate; UNKNOWN still supports visual/YUV field
 * evidence but must never be represented as a verified sensor clock.
 *
 * The input order is CameraManager.cameraIdList order and remains stable
 * for equal-priority candidates. No Android framework objects or camera
 * identifiers are retained outside this selection operation.
 */
object PhoneCameraCandidateSelector {
    enum class Facing { BACK, FRONT, OTHER }

    data class Candidate(
        val id: String,
        val facing: Facing,
        val advertisesYuv640x480: Boolean,
        val realtimeSensorClock: Boolean
    )

    fun select(candidates: List<Candidate>): Candidate? {
        var best: Candidate? = null
        var bestRank = Int.MAX_VALUE
        for (camera in candidates) {
            if (!camera.advertisesYuv640x480 || camera.id.isBlank()) continue
            val lens = when (camera.facing) {
                Facing.BACK -> 0
                Facing.FRONT -> 1
                Facing.OTHER -> 2
            }
            val rank = lens * 2 + if (camera.realtimeSensorClock) 0 else 1
            if (rank < bestRank) {
                best = camera
                bestRank = rank
            }
        }
        return best
    }
}
