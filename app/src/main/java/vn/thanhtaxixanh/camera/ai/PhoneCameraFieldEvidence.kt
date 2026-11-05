package vn.thanhtaxixanh.camera.ai

import java.util.zip.CRC32

/**
 * Owner-initiated, metadata-only Camera2 evidence. Capture completions prove
 * Camera2 request delivery, NOT actual presentation on the display.
 * No photos, location, faces, or model outputs are retained.
 */
class PhoneCameraFieldEvidence(
    private val cameraId: String,
    private val rearFacing: Boolean,
    private val sensorRealtime: Boolean,
    private val startMs: Long,
    private val targetMs: Long = 30_000L
) {
    init {
        require(cameraId.isNotBlank() && cameraId.length <= 80)
        require(startMs >= 0L && targetMs in 5_000L..120_000L)
    }
    data class Report(
        val text: String,
        val reviewable: Boolean,
        val elapsedMs: Long,
        val cameraCaptures: Long,
        val yuvCallbacks: Long,
        val sensorValid: Long,
        val yuvCopies: Long
    )

    private var previewConfigured = false
    private var captures = 0L
    private var capturesTimestamped = 0L
    private var callbacks = 0L
    private var valid = 0L
    private var rejected = 0L
    private var copied = 0L
    private var failed = 0L
    private var unsupported = 0L
    private var regressions = 0L
    private var lastImageMs = -1L
    private var lastSensorMs = -1L
    private var lastProbeAtMs = -1L
    private var lowestAge = Long.MAX_VALUE
    private var highestAge = -1L
    private var lastDigest = -1L
    private var minY = 255
    private var maxY = 0

    @Synchronized fun markPreviewConfigured() { previewConfigured=true }

    @Synchronized fun onCaptureCompleted(sensorTimestampNs: Long?) {
        captures++
        if(sensorTimestampNs!=null && sensorTimestampNs>0L) capturesTimestamped++
    }

    @Synchronized fun onImage(nowMs: Long, decision: Camera2SensorFrameGate.Decision) {
        callbacks++
        if(nowMs<startMs || (lastImageMs>=0L && nowMs<lastImageMs)) regressions++
        lastImageMs=nowMs
        if(decision.accepted) {
            valid++
            val sensor=decision.captureElapsedMs!!
            if(lastSensorMs>=0L && sensor<=lastSensorMs) regressions++
            lastSensorMs=sensor
            val age=decision.receiptAgeMs!!
            lowestAge=minOf(lowestAge,age)
            highestAge=maxOf(highestAge,age)
        } else rejected++
    }

    @Synchronized fun shouldProbe(nowMs:Long):Boolean {
        if(nowMs<startMs || (lastProbeAtMs>=0L && nowMs-lastProbeAtMs<1_000L))
            return false
        lastProbeAtMs=nowMs
        return true
    }

    @Synchronized fun onYuvProbe(result:PhoneCameraYuvProbe.Result) {
        copied++
        lastDigest=result.crc32
        minY=minOf(minY,result.minY)
        maxY=maxOf(maxY,result.maxY)
    }

    @Synchronized fun onProbeFailed(badFormat:Boolean=false) {
        failed++
        if(badFormat) unsupported++
    }

    @Synchronized fun summary(nowMs:Long):String {
        val ms=(nowMs-startMs).coerceAtLeast(0L)
        return "Camera2 " + (ms/1000L) + "/" + (targetMs/1000L) +
            "s · capture " + captures + " · YUV " + callbacks +
            " · mẫu ảnh " + copied + " · lỗi " + failed
    }

    @Synchronized fun finish(nowMs:Long, surfaceValid:Boolean):Report {
        val ms=(nowMs-startMs).coerceAtLeast(0L)
        val ready=ms>=targetMs && rearFacing && sensorRealtime &&
            previewConfigured && surfaceValid && captures>=100L &&
            callbacks>=30L && valid>=30L && copied>=10L &&
            regressions==0L && unsupported==0L
        val lines=listOf(
            "TTXPHONE01",
            "schema_version=1",
            "status=" + if(ready) "READY_FOR_INDEPENDENT_REVIEW" else "INCOMPLETE_OR_UNVERIFIED",
            "camera_id="+cameraId,
            "rear_facing="+rearFacing,
            "sensor_realtime="+sensorRealtime,
            "surface_request_configured="+previewConfigured,
            "surface_valid_at_export="+surfaceValid,
            "surface_valid_is_NOT_optical_proof=true",
            "start_elapsed_ms="+startMs,
            "elapsed_ms="+ms,
            "target_ms="+targetMs,
            "capture_completed="+captures,
            "capture_with_sensor_timestamp="+capturesTimestamped,
            "yuv_image_callbacks="+callbacks,
            "yuv_sensor_accepted="+valid,
            "yuv_sensor_rejected="+rejected,
            "min_sensor_age_ms="+if(lowestAge==Long.MAX_VALUE) "NA" else lowestAge,
            "max_sensor_age_ms="+if(highestAge<0L) "NA" else highestAge,
            "yuv_640x480_copies="+copied,
            "yuv_copy_failures="+failed,
            "unsupported_format="+unsupported,
            "clock_regressions="+regressions,
            "last_sample_crc32="+if(lastDigest<0L) "NA" else java.lang.Long.toHexString(lastDigest).padStart(8,'0'),
            "sample_min_y="+if(copied==0L) "NA" else minY,
            "sample_max_y="+if(copied==0L) "NA" else maxY,
            "saved_image_payload=false",
            "model_ground_truth_verified=false",
            "independent_screen_video_review_required=true",
            "criterion_8_not_automatically_PASS=true",
            "END_TTXPHONE01")
        return Report(lines.joinToString("\n")+"\n",ready,ms,captures,callbacks,valid,copied)
    }
}

/** Read actual copied Y/U/V pixels, preserving odd-crop chroma phase. */
object PhoneCameraYuvProbe {
    data class Result(val crc32:Long,val minY:Int,val maxY:Int)
    fun verify(frame:Yuv420FrameConverter.Frame):Result {
        require(frame.width==640 && frame.height==480)
        val crc=CRC32()
        var minY=255
        var maxY=0
        for(y in 0 until 480 step 8) for(x in 0 until 640 step 8) {
            val l=frame.y.sample(x,y)
            minY=minOf(minY,l)
            maxY=maxOf(maxY,l)
            crc.update(l)
            crc.update(frame.u.sample((x+frame.chromaXPhase)/2,(y+frame.chromaYPhase)/2))
            crc.update(frame.v.sample((x+frame.chromaXPhase)/2,(y+frame.chromaYPhase)/2))
        }
        return Result(crc.value,minY,maxY)
    }
}
