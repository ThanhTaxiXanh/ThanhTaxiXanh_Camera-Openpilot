package vn.thanhtaxixanh.camera.ai

import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent RTSP/RTP-H264/MediaCodec experimental player.
 * Android Surface display is direct; no VLC, Bitmap or PixelCopy on display.
 * Optional grayscale Y-plane is sampled from MediaCodec output if supported.
 * Maintains no hidden recording; caller must connect to its own camera Wi-Fi.
 */
class DirectRtspPlayer(
    private val network: Network,
    private val url: String,
    private val surface: Surface,
    private val isAiEnabled: () -> Boolean,
    private val status: (String) -> Unit,
    private val firstPicture: () -> Unit,
    private val onLuma: (ByteArray, Int, Int) -> Unit
) : AutoCloseable {
    data class Counters(val decoded: Long, val rendered: Long, val droppedRtp: Long, val ai: Long,
                        val noYuv: Boolean)
    private data class Sample(val data: ByteArray, val timestampUs: Long)
    private val alive = AtomicBoolean(false)
    private val shown = AtomicBoolean(false)
    private val noYuv = AtomicBoolean(false)
    private val decoded = AtomicLong()
    private val rendered = AtomicLong()
    private val dropped = AtomicLong()
    private val ai = AtomicLong()
    private val input = ArrayBlockingQueue<Int>(32)
    private val frames = ArrayBlockingQueue<Sample>(3)
    private var socket: Socket? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private var feeder: Thread? = null
    private var codecThread: HandlerThread? = null
    private var lastSampleMs = 0L

    fun counters() = Counters(decoded.get(), rendered.get(), dropped.get(), ai.get(), noYuv.get())
    fun start() {
        check(alive.compareAndSet(false, true)) { "Already started" }
        thread = Thread({ run() }, "TTX-RTSP-H264").apply { start() }
    }
    private data class Reply(val code: Int, val headers: Map<String, String>, val sdp: String) {
        fun header(s: String): String = headers[s.lowercase()].orEmpty()
    }

    /** Responses and RTP share the same TCP stream. This parser never scans
     * binary RTP bytes for newline delimiters (xADAS A500S: Invalid reply fix).
     */
    private fun request(
        wire: RtspWireReader, dst: BufferedOutputStream, seq: Int, verb: String,
        target: String, headers: Map<String, String> = emptyMap(),
        onEarlyMedia: (RtspWireReader.Event.Interleaved) -> Unit = {}
    ): Reply {
        status("RTSP " + verb + " (CSeq " + seq + ")…")
        val content = StringBuilder()
        content.append(verb).append(' ').append(target).append(" RTSP/1.0\r\n")
        content.append("CSeq: ").append(seq).append("\r\n")
        content.append("User-Agent: TTXCameraAI-Direct/0.9.1\r\n")
        headers.forEach { (k,v) -> content.append(k).append(": ").append(v).append("\r\n") }
        content.append("\r\n")
        dst.write(content.toString().toByteArray(Charsets.US_ASCII))
        dst.flush()

        var skipped = 0
        var firstChannel = -1
        val answer = wire.awaitResponse(seq) { frame ->
            skipped++
            if (firstChannel == -1) firstChannel = frame.channel
            onEarlyMedia(frame)
        }
        if (skipped > 0) status("RTSP " + verb + ": bỏ qua " + skipped +
            " gói nhị phân interleaved trước phản hồi (channel " + firstChannel + ")")
        status("RTSP " + verb + " → " + answer.code)
        return Reply(answer.code, answer.headers, answer.text)
    }
    private fun spsPps(sdp: String): Pair<ByteArray?,ByteArray?> {
        val fmtp = sdp.lineSequence().firstOrNull { it.contains("sprop-parameter-sets=") }
            ?: return null to null
        val raw = fmtp.substringAfter("sprop-parameter-sets=").substringBefore(';').split(',')
        if (raw.size < 2) return null to null
        return try {
            Base64.decode(raw[0].trim(),Base64.DEFAULT) to
                Base64.decode(raw[1].trim(),Base64.DEFAULT)
        } catch (_: Exception) { null to null }
    }
    private fun createDecoder(sdp: String) {
        val fmt = MediaFormat.createVideoFormat("video/avc",1280,720)
        val pair = spsPps(sdp)
        if (pair.first != null && pair.second != null) {
            fmt.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pair.first!!))
            fmt.setByteBuffer("csd-1",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pair.second!!))
        }
        fmt.setInteger(MediaFormat.KEY_PRIORITY,0)
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY,1)
        val t = HandlerThread("TTX-Codec",Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        codecThread=t
        val decoder=MediaCodec.createDecoderByType("video/avc")
        codec=decoder
        decoder.setCallback(object:MediaCodec.Callback() {
            override fun onInputBufferAvailable(c:MediaCodec,i:Int) { if (alive.get()) input.offer(i) }
            override fun onOutputFormatChanged(c:MediaCodec,f:MediaFormat) {
                status("MediaCodec "+c.name+" "+f.getInteger(MediaFormat.KEY_WIDTH)+
                    "x"+f.getInteger(MediaFormat.KEY_HEIGHT))
            }
            override fun onError(c:MediaCodec,e:MediaCodec.CodecException) {
                status("MediaCodec: "+e.diagnosticInfo)
                alive.set(false)
                try { socket?.close() } catch (_:Exception) {}
            }
            override fun onOutputBufferAvailable(c:MediaCodec,i:Int,info:MediaCodec.BufferInfo) {
                if (!alive.get()) return
                try {
                    decoded.incrementAndGet()
                    val now=SystemClock.elapsedRealtime()
                    if (isAiEnabled() && !noYuv.get() && now-lastSampleMs>=1300L) {
                        lastSampleMs=now
                        try {
                            val image=c.getOutputImage(i)
                            if (image==null) noYuv.set(true)
                            else image.use { sampleLuma(it) }
                        } catch (_:Exception) {
                            noYuv.set(true)
                            status("MediaCodec Surface không xuất được YUV, vẫn phát video.")
                        }
                    }
                    c.releaseOutputBuffer(i,true)
                    rendered.incrementAndGet()
                    if (shown.compareAndSet(false,true)) firstPicture()
                } catch (e:Exception) { status("MediaCodec output "+e.javaClass.simpleName) }
            }
        },Handler(t.looper))
        try {
            decoder.configure(fmt,surface,null,0)
        } catch (e:Exception) {
            if (Build.VERSION.SDK_INT>=30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY,0)
            decoder.configure(fmt,surface,null,0)
        }
        decoder.start()
        feeder=Thread({
            while (alive.get()) {
                try {
                    val sample=frames.poll(500,TimeUnit.MILLISECONDS) ?: continue
                    val index=input.poll(500,TimeUnit.MILLISECONDS) ?: continue
                    val dest=decoder.getInputBuffer(index) ?: continue
                    dest.clear()
                    if (sample.data.size>dest.remaining()) {
                        dropped.incrementAndGet()
                        decoder.queueInputBuffer(index,0,0,sample.timestampUs,0)
                    } else {
                        dest.put(sample.data)
                        decoder.queueInputBuffer(index,0,sample.data.size,sample.timestampUs,0)
                    }
                } catch (_:InterruptedException) { break }
                catch (e:Exception) { if (alive.get()) status("Codec feed: "+e.javaClass.simpleName) }
            }
        },"TTX-CodecFeed").apply{ start() }
    }
    private fun sampleLuma(image:Image) {
        val p=image.planes.firstOrNull() ?: return
        val crop:Rect=image.cropRect
        val w=320;val h=180
        if (crop.width()<w || crop.height()<h) return
        val data=ByteArray(w*h)
        val buffer=p.buffer
        for (y in 0 until h) {
            val yy=crop.top+y*crop.height()/h
            for(x in 0 until w) {
                val xx=crop.left+x*crop.width()/w
                val at=yy*p.rowStride+xx*p.pixelStride
                if (at in 0 until buffer.limit()) data[y*w+x]=buffer.get(at)
            }
        }
        ai.incrementAndGet()
        onLuma(data,w,h)
    }
    private fun enqueue(bytes:ByteArray,ts:Long) {
        val s=Sample(bytes,ts*1000000L/90000L)
        if (!frames.offer(s)) {
            frames.poll()
            frames.offer(s)
            dropped.incrementAndGet()
        }
    }
    private fun run() {
        try {
            val uri=URI(url)
            val host=uri.host ?: error("Missing RTSP host")
            val port=if(uri.port>0)uri.port else 554
            val sock=network.socketFactory.createSocket()
            socket=sock
            sock.tcpNoDelay=true;sock.soTimeout=6000
            sock.connect(InetSocketAddress(host,port),4500)
            val src=BufferedInputStream(sock.getInputStream(),65536)
            val wire = RtspWireReader(src)
            val dst=BufferedOutputStream(sock.getOutputStream(),8192)
            require(request(wire,dst,1,"OPTIONS",url).code==200){"OPTIONS failed"}
            val describe=request(wire,dst,2,"DESCRIBE",url,
                mapOf("Accept" to "application/sdp"))
            require(describe.code==200 && describe.sdp.contains("m=video")) {
                "DESCRIBE "+describe.code
            }
            val base=describe.header("content-base").ifBlank { url }
            val videosection=describe.sdp.substringAfter("m=video").substringBefore("\nm=")
            val control=videosection.lines().firstOrNull {
                it.trim().startsWith("a=control:")
            }?.trim()?.substringAfter(':') ?: "track1"
            val track=if(control.startsWith("rtsp://"))control
                else URI(base).resolve(control).toString()
            val setup=request(wire,dst,3,"SETUP",track,
                mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            require(setup.code==200) { "SETUP "+setup.code+" "+track }
            val session=setup.header("session").substringBefore(';')
            require(session.isNotEmpty()){"RTSP session missing"}
            val aggregate=if(describe.sdp.lines().any { it.trim()=="a=control:*" })base else url
            // Some firmware negotiates channels other than 0-1.
            val rtpChannel = Regex("interleaved=(\\d+)-").find(setup.header("transport"))
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val earlyMedia = ArrayDeque<ByteArray>()
            var earlyBytes = 0
            val play=request(wire,dst,4,"PLAY",aggregate,
                mapOf("Session" to session)) { item ->
                // Limit retained early RTP data; they may arrive before PLAY reply.
                if (item.channel == rtpChannel && earlyMedia.size < 24 &&
                    earlyBytes + item.payload.size <= 96 * 1024) {
                    earlyMedia.addLast(item.payload)
                    earlyBytes += item.payload.size
                }
            }
            require(play.code==200){ "PLAY "+play.code+" "+aggregate }
            status("RTSP H264 PLAY 200 · channel=" + rtpChannel +
                " · pre-PLAY RTP " + earlyMedia.size)
            createDecoder(describe.sdp)
            val assemble=Assembler(::enqueue,dropped)
            earlyMedia.forEach { assemble.accept(it) }
            while(alive.get()) {
                val packet = try { wire.next() }
                catch (_:java.net.SocketTimeoutException) { continue }
                when (packet) {
                    is RtspWireReader.Event.Interleaved ->
                        if (packet.channel == rtpChannel) assemble.accept(packet.payload)
                    is RtspWireReader.Event.Reply -> {
                        // Camera may transmit keepalive responses on the same TCP socket.
                        if (packet.code >= 400) status("RTSP server " + packet.code)
                    }
                }
            }
        } catch (e:Exception) {
            if(alive.get())status("Direct RTSP: "+(e.message ?: e.javaClass.simpleName))
        } finally {
            alive.set(false)
            try{socket?.close()}catch(_:Exception){}
        }
    }
    private class Assembler(
        val emit:(ByteArray,Long)->Unit, val lost:AtomicLong
    ) {
        private val out=ByteArrayOutputStream(65536)
        private var ts=-1L
        private var seq=-1
        private var broken=false
        private val prefix=byteArrayOf(0,0,0,1)
        private fun add(packet:ByteArray,offset:Int,length:Int) {
            if(length<1 || out.size()+length>1_500_000){broken=true;return}
            out.write(prefix);out.write(packet,offset,length)
        }
        private fun flush() {
            if(out.size()>0 && !broken)emit(out.toByteArray(),ts)
            out.reset();broken=false
        }
        fun accept(p:ByteArray) {
            if(p.size<13 || (p[0].toInt() and 0xC0)!=0x80)return
            val cc=p[0].toInt() and 15
            var o=12+4*cc
            if(o>=p.size)return
            if(p[0].toInt() and 16!=0) {
                if(o+4>p.size)return
                val len=((p[o+2].toInt() and 255) shl 8)or(p[o+3].toInt() and 255)
                o+=4+len*4
            }
            if(o>=p.size)return
            val mark=(p[1].toInt() and 128)!=0
            val nextSeq=((p[2].toInt() and 255) shl 8)or(p[3].toInt() and 255)
            val nextTs=((p[4].toLong() and 255) shl 24)or
                ((p[5].toLong() and 255) shl 16)or
                ((p[6].toLong() and 255) shl 8)or(p[7].toLong() and 255)
            if(ts>=0 && ts!=nextTs) flush()
            if(seq>=0 && ((seq+1) and 65535)!=nextSeq){broken=true;lost.incrementAndGet()}
            seq=nextSeq;ts=nextTs
            when(p[o].toInt() and 31) {
                in 1..23 -> add(p,o,p.size-o)
                24 -> { o++
                    while(o+2<=p.size) {
                        val len=((p[o].toInt() and 255) shl 8)or(p[o+1].toInt() and 255)
                        o+=2
                        if(len<1 || o+len>p.size){broken=true;break}
                        add(p,o,len);o+=len
                    }
                }
                28 -> {
                    if(o+2>=p.size){broken=true;return}
                    val typ=p[o+1].toInt() and 255
                    if(typ and 128!=0) {
                        out.write(prefix)
                        out.write((p[o].toInt() and 224)or(typ and 31))
                    } else if(out.size()==0) broken=true
                    out.write(p,o+2,p.size-o-2)
                }
                else -> broken=true
            }
            if(mark)flush()
        }
    }
    override fun close() {
        alive.set(false)
        try{socket?.close()}catch(_:Exception){}
        thread?.interrupt()
        feeder?.interrupt()
        val c=codec
        codec=null
        Thread({
            try{c?.stop()}catch(_:Exception){}
            try{c?.release()}catch(_:Exception){}
            codecThread?.quitSafely()
        },"TTX-CodecClose").start()
    }
}
