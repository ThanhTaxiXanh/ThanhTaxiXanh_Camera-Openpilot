package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Pure-data observation. Inference is experimental and NOT calibrated for driving safety. */
data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
data class Vehicle(val box: RectF, val label: String, val confidence: Float, val approxM: Float)
data class Observation(
    val width: Int, val height: Int, val left: Segment?, val right: Segment?,
    val vehicle: Vehicle?, val note: String
)

class VisionEngine(private val context: Context) {
    private val cvReady = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false }
    private var detector: ObjectDetector? = null

    /** Requires a metadata-enabled TFLite Task Vision ObjectDetector model. */
    fun reloadModel(): String {
        detector?.close()
        detector = null
        val f = File(context.filesDir, "vehicles.tflite")
        if (!f.exists()) return "Chưa có mô hình nhận diện xe"
        return try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(0.45f).setMaxResults(8).build()
            detector = ObjectDetector.createFromFileAndOptions(context, f.absolutePath, options)
            "Đã nạp mô hình xe"
        } catch (e: Exception) {
            "Mô hình không tương thích: ${e.message?.take(90)}"
        }
    }

    fun analyze(image: Bitmap, horizontalFovDegrees: Double = 100.0): Observation {
        val w = image.width
        val h = image.height
        if (!cvReady) return Observation(w, h, null, null, null, "Không tải được OpenCV")
        var left: Segment? = null
        var right: Segment? = null
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val mask = Mat()
        val masked = Mat()
        val lines = Mat()
        try {
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 60.0, 145.0)
            edges.copyTo(mask)
            mask.setTo(Scalar(0.0))
            val region = MatOfPoint(
                Point(w * 0.05, h * 0.98), Point(w * 0.45, h * 0.54),
                Point(w * 0.55, h * 0.54), Point(w * 0.95, h * 0.98)
            )
            Imgproc.fillPoly(mask, listOf(region), Scalar(255.0))
            region.release()
            Core.bitwise_and(edges, mask, masked)
            Imgproc.HoughLinesP(masked, lines, 1.0, Math.PI / 180.0, 36, 28.0, 30.0)
            val negatives = mutableListOf<Segment>()
            val positives = mutableListOf<Segment>()
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                if (v.size < 4) continue
                val x1 = v[0].toFloat(); val y1 = v[1].toFloat()
                val x2 = v[2].toFloat(); val y2 = v[3].toFloat()
                val dx = x2 - x1
                if (abs(dx) < 8f) continue
                val slope = (y2 - y1) / dx
                if (abs(slope) < 0.45f || abs(slope) > 6.0f) continue
                val s = Segment(x1, y1, x2, y2)
                if (slope < 0) negatives += s else positives += s
            }
            fun average(list: List<Segment>): Segment? {
                if (list.isEmpty()) return null
                val a = list.sortedByDescending { abs(it.y2 - it.y1) }.take(12)
                return Segment(a.map { it.x1 }.average().toFloat(), a.map { it.y1 }.average().toFloat(),
                    a.map { it.x2 }.average().toFloat(), a.map { it.y2 }.average().toFloat())
            }
            left = average(negatives)
            right = average(positives)
        } catch (_: Exception) {
            // Frame processing may fail on some GPU/colour formats.
        } finally {
            rgba.release(); gray.release(); edges.release(); mask.release(); masked.release(); lines.release()
        }
        var lead: Vehicle? = null
        try {
            val result = detector?.detect(TensorImage.fromBitmap(image)).orEmpty()
            val candidates = result.mapNotNull { detection ->
                val c = detection.categories.maxByOrNull { it.score } ?: return@mapNotNull null
                val name = c.label.lowercase()
                if (name !in setOf("car", "truck", "bus", "motorcycle", "motorbike", "vehicle")) return@mapNotNull null
                val box = detection.boundingBox
                val cx = box.centerX() / w
                if (cx < 0.29f || cx > 0.71f) return@mapNotNull null
                val pixels = box.width()
                if (pixels < 8f) return@mapNotNull null
                // Pinhole geometry: the width of the target is ASSUMED. Never use for braking.
                val widthMeters = if (name == "motorcycle" || name == "motorbike") 0.8 else 1.8
                val focalPx = w / (2.0 * tan(Math.toRadians(horizontalFovDegrees / 2.0)))
                val distance = (widthMeters * focalPx / pixels).toFloat()
                if (!distance.isFinite() || distance < 1.0f || distance > 150.0f) return@mapNotNull null
                Vehicle(RectF(box), name, c.score, distance)
            }
            lead = candidates.minByOrNull { it.approxM }
        } catch (_: Exception) {
            // An incompatible model must never lead to fabricated distances.
        }
        val label = if (detector == null) "Làn đường: thử nghiệm · Chưa có mô hình xe"
                    else "Làn đường + phát hiện xe: THỬ NGHIỆM"
        return Observation(w, h, left, right, lead, label)
    }

    fun close() { detector?.close(); detector = null }
}
