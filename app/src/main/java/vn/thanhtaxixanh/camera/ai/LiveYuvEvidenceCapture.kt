package vn.thanhtaxixanh.camera.ai

/**
 * Explicitly armed, bounded offscreen YUV collector. Never called from RTP,
 * preview Surface or UI paths. Incomplete captures are discarded at epoch
 * changes; receipt timestamps are not physical exposure timestamps.
 */
class LiveYuvEvidenceCapture(private val target: Int = 4) {
    init { require(target in 2..OfflineYuvFixture.MAX_FRAMES) }
    private val frames=ArrayList<OfflineYuvFixture.Frame>(target)
    private var armed=false

    @Synchronized fun arm() { frames.clear(); armed=true }
    @Synchronized fun cancel() { frames.clear(); armed=false }
    @Synchronized fun isArmed(): Boolean=armed
    @Synchronized fun count(): Int=frames.size

    /** A complete four-frame sequence is produced exactly once. */
    @Synchronized fun accept(frame:Yuv420FrameConverter.Frame,
        atMs:Long):OfflineYuvFixture.Sequence? {
        if (!armed) return null
        val last=frames.lastOrNull()?.receivedMs
        if (frame.width!=OfflineYuvFixture.WIDTH ||
            frame.height!=OfflineYuvFixture.HEIGHT || atMs<0 ||
            (last!=null && (atMs<=last ||
                atMs-last>A500sRawVisionBridge.MAX_GAP_MS))) {
            cancel()
            return null
        }
        fun planeBytes(p:Yuv420FrameConverter.Plane,w:Int,h:Int):ByteArray {
            val data=ByteArray(w*h)
            for(y in 0 until h) for(x in 0 until w)
                data[y*w+x]=p.sample(x,y).toByte()
            return data
        }
        return try {
            frames.add(OfflineYuvFixture.Frame(atMs,
                planeBytes(frame.y,640,480),
                planeBytes(frame.u,320,240),
                planeBytes(frame.v,320,240)))
            if(frames.size!=target) null else {
                armed=false
                val complete=OfflineYuvFixture.Sequence(frames.toList())
                frames.clear()
                complete
            }
        } catch (_:IllegalArgumentException) {
            cancel();null
        } catch (_:IndexOutOfBoundsException) {
            cancel();null
        }
    }
}
