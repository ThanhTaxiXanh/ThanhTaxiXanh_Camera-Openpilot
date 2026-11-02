package vn.thanhtaxixanh.camera.ai

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Explicit user-requested export of two camera frames for offline alignment
 * research. Does not include a Wi-Fi password, precise location or any model.
 * Treat output as sensitive: car number plates/people may appear in frames.
 */
object CameraFramePairExporter {
    fun save(
        file: File,
        previous: Bitmap,
        current: Bitmap,
        previousElapsedMs: Long,
        currentElapsedMs: Long
    ) {
        require(!previous.isRecycled && !current.isRecycled)
        require(currentElapsedMs - previousElapsedMs >= 120L) {
            "Frames must be separated by >=120 milliseconds"
        }
        require(previous.width > 0 && previous.height > 0)
        require(previous.width == current.width && previous.height == current.height)
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { archive ->
            fun putBytes(path: String, bytes: ByteArray) {
                archive.putNextEntry(ZipEntry(path))
                archive.write(bytes)
                archive.closeEntry()
            }
            fun encode(frame: Bitmap): ByteArray {
                val output = java.io.ByteArrayOutputStream()
                check(frame.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "Image JPEG encoding failed"
                }
                return output.toByteArray()
            }
            val imageA = encode(previous)
            val imageB = encode(current)
            val identical = imageA.contentEquals(imageB)
            putBytes("frame_01.jpg", imageA)
            putBytes("frame_02.jpg", imageB)
            val info = JSONObject().apply {
                put("format", "ttx-camera-ai-frame-pair-v1")
                put("source", "70mai-A500S-RTSP-LibVLC")
                put("width", previous.width)
                put("height", previous.height)
                put("frame1_elapsed_ms", previousElapsedMs)
                put("frame2_elapsed_ms", currentElapsedMs)
                put("interval_ms", currentElapsedMs - previousElapsedMs)
                put("identical_encoded_frames", identical)
                put("frame_pair_diagnostic", if (identical)
                    "Identical images: either scene is static, camera had no new frame, or PixelCopy repeated the same buffer"
                    else "Different images: visual buffer changed between samples")
                put("warp_calibrated", false)
                put("scene_export_requires_user_consent", true)
                put("notes", "PixelCopy frames from video surface; not original camera YUV and not calibrated inputs")
            }
            putBytes("frame_metadata.json", info.toString(2).toByteArray(Charsets.UTF_8))
        }
    }
}
