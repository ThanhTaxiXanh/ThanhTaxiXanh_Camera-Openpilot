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
import java.net.DatagramPacket
import java.net.InetAddress
import java.security.SecureRandom
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
    private val onLuma: (ByteArray, Int, Int) -> Unit,
    private val onSessionInterrupted: (String) -> Unit,
    private val preferUdp: Boolean = false
) : AutoCloseable {
    data class Counters(
        val decoded: Long, val rendered: Long, val droppedRtp: Long,
        val ai: Long, val noYuv: Boolean,
        val receivedAccessUnits: Long, val submittedAccessUnits: Long,
        val compressedBytes: Long, val inputWaitMs: Long,
        val queueDepth: Int, val peakQueueDepth: Long, val overflows: Long,
        val keepAliveSent: Long, val keepAliveOk: Long,
        val keepAliveUnsupported: Long, val reconnectReason: String,
        val transportName: String, val udpPackets: Long,
        val rtp: RtpReceiverDiagnostics.Snapshot,
        val rtcpSent: Long, val discardedAccessUnits: Long
    )
    private data class Sample(val data: ByteArray, val timestampUs: Long)
    private val alive = AtomicBoolean(false)
    private val shown = AtomicBoolean(false)
    private val noYuv = AtomicBoolean(false)
    private val decoded = AtomicLong()
    private val rendered = AtomicLong()
    private val dropped = AtomicLong()
    private val ai = AtomicLong()
    private val receivedAccessUnits = AtomicLong()
    private val submittedAccessUnits = AtomicLong()
    private val compressedBytes = AtomicLong()
    private val inputWaitMs = AtomicLong()
    private val queueOverflows = AtomicLong()
    private val queuePeak = AtomicLong()
    private val keepAliveSent = AtomicLong()
    private val keepAliveOk = AtomicLong()
    private val keepAliveUnsupported = AtomicLong()
    private val udpPackets = AtomicLong()
    private val rtpDiagnostics = RtpReceiverDiagnostics()
    private val discardedAccessUnits = AtomicLong()
    private val rtcpSent = AtomicLong()
    private val rtcpReporterSsrc = SecureRandom().nextInt()
    @Volatile private var activeTransportName = "RTSP TCP"
    @Volatile private var lastNetworkFailure = ""
    private val explicitlyClosed = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    // No artificial PTS wall-clock pacing. On the A500S input timing drifted
    // over long sessions and the V0.9.5 feeder imposed an additional wait.
    // We keep the camera's original PTS for MediaCodec and measure actual input.
    private val input = ArrayBlockingQueue<Int>(32)
    // 48 complete access units (approximately 1–2 seconds at 25–30fps).
    // Never discard arbitrary P frames: the following frames depend on them.
    private val frames = ArrayBlockingQueue<Sample>(48)
    private var socket: Socket? = null
    private var udpPorts: A500sUdpPortPair? = null
    private var udpThread: Thread? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private var feeder: Thread? = null
    private var codecThread: HandlerThread? = null
    private var lastSampleMs = 0L

    fun counters() = Counters(
        decoded.get(), rendered.get(), dropped.get(), ai.get(), noYuv.get(),
        receivedAccessUnits.get(), submittedAccessUnits.get(),
        compressedBytes.get(), inputWaitMs.get(),
        frames.size, queuePeak.get(), queueOverflows.get(),
        keepAliveSent.get(), keepAliveOk.get(), keepAliveUnsupported.get(),
        lastNetworkFailure, activeTransportName, udpPackets.get(),
        rtpDiagnostics.snapshot(), rtcpSent.get(), discardedAccessUnits.get()
    )
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
        // Fastest possible decode is not the same as evenly paced display.
        // Low latency was previously forced on despite this being a smooth
        // playback mode; disable the optional vendor-specific low-latency hint.
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY,0)
        val t = HandlerThread("TTX-Codec",Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        codecThread=t
        val codecHandler = Handler(t.looper)
        val decoder=MediaCodec.createDecoderByType("video/avc")
        codec=decoder
        decoder.setCallback(object:MediaCodec.Callback() {
            override fun onInputBufferAvailable(c:MediaCodec,i:Int) { if (alive.get()) input.offer(i) }
            override fun onOutputFormatChanged(c:MediaCodec,f:MediaFormat) {
                status("MediaCodec "+c.name+" "+f.getInteger(MediaFormat.KEY_WIDTH)+
                    "x"+f.getInteger(MediaFormat.KEY_HEIGHT))
            }
            override fun onError(c:MediaCodec,e:MediaCodec.CodecException) {
                lastNetworkFailure = "MediaCodec: " + e.diagnosticInfo
                status(lastNetworkFailure)
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
                    // Critical V0.9.5 change:
                    // NEVER retain a decoded hardware output buffer while
                    // waiting for a target PTS. Input frame pacing is handled
                    // on the feeder thread, preserving codec output slots.
                    if (info.size > 0) {
                        c.releaseOutputBuffer(i, true)
                        rendered.incrementAndGet()
                        if (shown.compareAndSet(false,true)) firstPicture()
                    } else {
                        c.releaseOutputBuffer(i, false)
                    }
                } catch (e:Exception) { status("MediaCodec output "+e.javaClass.simpleName) }
            }
        },codecHandler)
        try {
            decoder.configure(fmt,surface,null,0)
        } catch (e:Exception) {
            if (Build.VERSION.SDK_INT>=30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY,0)
            decoder.configure(fmt,surface,null,0)
        }
        decoder.start()
        feeder=Thread({
            try {
                while (alive.get()) {
                    // V0.9.6 rollback: submit complete H.264 access units
                    // without sleeping on a PTS clock or accumulating a
                    // second artificial jitter buffer. Never reorder/drop
                    // compressed reference pictures.
                    val sample=frames.poll(250,TimeUnit.MILLISECONDS) ?: continue
                    val waitStart=SystemClock.elapsedRealtime()
                    var index:Int?=null
                    while (alive.get() && index == null)
                        index=input.poll(250,TimeUnit.MILLISECONDS)
                    inputWaitMs.addAndGet(
                        (SystemClock.elapsedRealtime()-waitStart).coerceAtLeast(0L))
                    if (!alive.get() || index == null) break

                    val dst=decoder.getInputBuffer(index)
                        ?: throw IllegalStateException("MediaCodec returned a null input buffer")
                    dst.clear()
                    if (sample.data.size > dst.remaining()) {
                        throw IllegalStateException(
                            "H264 access unit exceeds decoder input buffer: "+
                            sample.data.size+" > "+dst.remaining())
                    }
                    dst.put(sample.data)
                    decoder.queueInputBuffer(index,0,sample.data.size,sample.timestampUs,0)
                    submittedAccessUnits.incrementAndGet()
                }
            } catch (_:InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error:Exception) {
                if (alive.get()) {
                    lastNetworkFailure = "Codec feed: " +
                        (error.message ?: error.javaClass.simpleName)
                    status(lastNetworkFailure)
                    alive.set(false)
                    try { socket?.close() } catch (_:Exception) {}
                }
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
        val sample=Sample(bytes,ts*1000000L/90000L)
        // Applying bounded backpressure preserves the H264 reference chain.
        // If decode stalls for 900 ms, fail with a clear error rather than
        // silently dropping a compressed P-frame and corrupting the GOP.
        val accepted = frames.offer(sample, 900L, TimeUnit.MILLISECONDS)
        if (!accepted) {
            queueOverflows.incrementAndGet()
            throw IllegalStateException(
                "Decoder không theo kịp: hàng đợi 48 khung đầy hơn 900 ms. " +
                "Dừng Direct để tránh bỏ mất H264 P-frame; thử lại hoặc dùng VLC 800 ms."
            )
        }
        receivedAccessUnits.incrementAndGet()
        compressedBytes.addAndGet(bytes.size.toLong())
        queuePeak.accumulateAndGet(frames.size.toLong()) { a, b -> maxOf(a,b) }
    }
    private fun run() {
        var errorMessage = ""
        try {
            val uri=URI(url)
            val host=uri.host ?: error("Missing RTSP host")
            val port=if(uri.port>0)uri.port else 554
            val sock=network.socketFactory.createSocket()
            socket=sock
            sock.tcpNoDelay=true
            sock.keepAlive=true
            sock.soTimeout=1500
            sock.connect(InetSocketAddress(host,port),4500)
            val src=BufferedInputStream(sock.getInputStream(),65536)
            val wire = RtspWireReader(src)
            val dst=BufferedOutputStream(sock.getOutputStream(),8192)
            val describe=request(wire,dst,1,"DESCRIBE",url,
                mapOf("Accept" to "application/sdp"))
            require(describe.code==200 && describe.sdp.contains("m=video")) {
                "DESCRIBE "+describe.code
            }
            val base=describe.header("content-base").ifBlank { url }
            val videosection=describe.sdp.substringAfter("m=video").substringBefore("\nm=")
            val control=videosection.lines().firstOrNull {
                it.trim().startsWith("a=control:")
            }?.trim()?.substringAfter(':') ?: "track1"
            val track=if(control.startsWith("rtsp://")) control
                else URI(base).resolve(control).toString()
            val requestedUdp = if (preferUdp) A500sUdpPortPair.allocate(network) else null
            udpPorts = requestedUdp
            val setupHeader = requestedUdp?.transportHeader
                ?: "RTP/AVP/TCP;unicast;interleaved=0-1"
            status("SETUP requested " + if (preferUdp) "RTP/UDP" else "RTP/TCP")
            val setup=request(wire,dst,2,"SETUP",track,
                mapOf("Transport" to setupHeader))
            require(setup.code==200) { "SETUP "+setup.code+" "+track }
            val negotiated = setup.header("transport")
            val useUdp = requestedUdp != null &&
                RtspTransportNegotiation.isUdpTransport(negotiated)
            val useTcp = RtspTransportNegotiation.isTcpTransport(negotiated)
            require(useUdp || useTcp) {
                "Camera SETUP transport unsupported: "+negotiated.take(120)
            }
            activeTransportName = if (useUdp) "RTP/UDP" else "RTP/TCP"
            if (!useUdp) {
                requestedUdp?.close()
                udpPorts = null
            }
            status("Camera chọn: " + activeTransportName)
            val sessionHeader = setup.header("session")
            val session = sessionHeader.substringBefore(';').trim()
            require(session.isNotEmpty()){"RTSP session missing"}
            val timeoutSecs = RtspSessionMaintenance.timeoutSeconds(sessionHeader)
            val intervalMs = RtspSessionMaintenance.intervalMs(timeoutSecs)
            val aggregate=if(describe.sdp.lines().any { it.trim()=="a=control:*" })base else url
            val rtpChannel = Regex("interleaved=(\\d+)-").find(negotiated)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val rtcpChannel = Regex("interleaved=\\d+-(\\d+)").find(negotiated)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val serverRtcpPort = Regex("server_port=\\d+-(\\d+)").find(negotiated)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (useUdp && serverRtcpPort == null)
                status("SETUP UDP không trả server_port cho RTCP: chỉ đo RTP")
            val earlyMedia = ArrayDeque<ByteArray>()
            var earlyBytes = 0
            val play=request(wire,dst,3,"PLAY",aggregate,
                mapOf("Session" to session)) { item ->
                if (item.channel == rtpChannel && earlyMedia.size < 24 &&
                    earlyBytes + item.payload.size <= 96 * 1024) {
                    earlyMedia.addLast(item.payload)
                    earlyBytes += item.payload.size
                }
            }
            require(play.code==200){ "PLAY "+play.code+" "+aggregate }
            status("RTSP PLAY 200 · session timeout="+timeoutSecs+
                "s · keepalive mỗi "+intervalMs/1000+"s")
            createDecoder(describe.sdp)
            val assemble=Assembler(::enqueue,dropped,rtpDiagnostics,discardedAccessUnits)
            if (!useUdp) earlyMedia.forEach { assemble.accept(it) }

            val lastRtpAt = AtomicLong(SystemClock.elapsedRealtime())
            if (useUdp) {
                val pair = udpPorts ?: error("UDP negotiation lost socket")
                val t = Thread({
                    val buf = ByteArray(65536)
                    try {
                        while (alive.get()) {
                            try {
                                val packet = pair.receivePacket(buf)
                                // Bind to the selected Wi-Fi Network and accept
                                // video packets from the camera host only.
                                if (packet.address.hostAddress == host && packet.length >= 12) {
                                    lastRtpAt.set(SystemClock.elapsedRealtime())
                                    udpPackets.incrementAndGet()
                                    assemble.accept(packet.data.copyOfRange(
                                        packet.offset, packet.offset + packet.length))
                                }
                            } catch (_: java.net.SocketTimeoutException) {
                                // Camera may have paused briefly; control thread
                                // decides when the lack of RTP is fatal.
                            }
                        }
                    } catch (e: Exception) {
                        if (alive.get()) {
                            lastNetworkFailure = "RTP UDP receive: " +
                                (e.message ?: e.javaClass.simpleName)
                            alive.set(false)
                            try { socket?.close() } catch (_: Exception) {}
                        }
                    }
                }, "TTX-70mai-RTP-UDP")
                udpThread = t
                t.priority = Thread.NORM_PRIORITY + 1
                t.start()
            }

            var nextSequence = 4
            var nextRtcpAt = SystemClock.elapsedRealtime() + 5000L
            var nextKeepAliveAt = SystemClock.elapsedRealtime() + intervalMs
            var pendingSeq = -1
            var pendingAt = 0L
            var preferredMethod = "GET_PARAMETER"
            var unsupportedBoth = false
            while(alive.get()) {
                val now=SystemClock.elapsedRealtime()
                if (now-lastRtpAt.get()>12000L) {
                    error("RTP timeout: no H264 packets for 12 seconds")
                }
                // xADAS 0.2.7 also issues RTCP receiver reports every
                // approximately five seconds. This is feedback, not a
                // guarantee that the camera will increase its frame rate.
                if (now >= nextRtcpAt) {
                    nextRtcpAt = now + 5000L
                    val feedback = RtcpReceiverReport.compound(
                        rtcpReporterSsrc, rtpDiagnostics.snapshot())
                    if (feedback.isNotEmpty()) {
                        try {
                            if (useUdp && serverRtcpPort != null) {
                                udpPorts?.rtcp?.send(DatagramPacket(
                                    feedback, feedback.size,
                                    InetAddress.getByName(host), serverRtcpPort))
                                rtcpSent.incrementAndGet()
                            } else if (!useUdp && rtcpChannel in 0..255) {
                                val header = byteArrayOf(0x24, rtcpChannel.toByte(),
                                    (feedback.size ushr 8).toByte(), feedback.size.toByte())
                                dst.write(header)
                                dst.write(feedback)
                                dst.flush()
                                rtcpSent.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            status("RTCP feedback lỗi: " + e.javaClass.simpleName)
                        }
                    }
                }
                if (pendingSeq>=0 && now-pendingAt>9000L) {
                    // Do not block the video reader waiting for keepalive replies.
                    status("Keepalive CSeq "+pendingSeq+" chưa có phản hồi; tiếp tục nhận RTP")
                    pendingSeq=-1
                }
                if (!unsupportedBoth && pendingSeq<0 && now>=nextKeepAliveAt) {
                    val seq=nextSequence++
                    val message=RtspSessionMaintenance.request(
                        preferredMethod, aggregate, session, seq)
                    dst.write(message)
                    dst.flush()
                    keepAliveSent.incrementAndGet()
                    pendingSeq=seq
                    pendingAt=now
                    nextKeepAliveAt=now+intervalMs
                    status("RTSP keepalive "+preferredMethod+" CSeq "+seq+" (timeout="+timeoutSecs+"s)")
                }

                val packet = try { wire.next() }
                catch (_:java.net.SocketTimeoutException) { continue }
                when (packet) {
                    is RtspWireReader.Event.Interleaved -> {
                        if (packet.channel == rtpChannel) {
                            lastRtpAt.set(SystemClock.elapsedRealtime())
                            assemble.accept(packet.payload)
                        }
                    }
                    is RtspWireReader.Event.Reply -> {
                        val seq=packet.header("cseq").toIntOrNull()
                        if (seq!=null && seq==pendingSeq) {
                            pendingSeq=-1
                            if (packet.code in 200..299) {
                                keepAliveOk.incrementAndGet()
                            } else {
                                keepAliveUnsupported.incrementAndGet()
                                status("Keepalive "+preferredMethod+" → "+packet.code)
                                if (preferredMethod=="GET_PARAMETER") {
                                    preferredMethod="SET_PARAMETER"
                                    nextKeepAliveAt=SystemClock.elapsedRealtime()+1000L
                                } else {
                                    unsupportedBoth=true
                                    status("Camera từ chối cả 2 keepalive; sẽ tự kết nối lại khi cần")
                                }
                            }
                        } else if (packet.code>=400) {
                            status("RTSP server "+packet.code+
                                " (CSeq "+(seq?.toString() ?: "?")+")")
                        }
                    }
                }
            }
            if (!explicitlyClosed.get()) {
                errorMessage = lastNetworkFailure.ifBlank { "RTSP ended unexpectedly" }
            }
        } catch (e:Exception) {
            if (!explicitlyClosed.get()) {
                errorMessage = (e.javaClass.simpleName+": "+
                    (e.message ?: "No details")).take(200)
                lastNetworkFailure=errorMessage
                status("Direct RTSP: "+errorMessage)
            }
        } finally {
            // Releasing the hardware decoder is mandatory on every TCP reset.
            cleanup()
            if (!explicitlyClosed.get()) {
                onSessionInterrupted(errorMessage.ifBlank {
                    lastNetworkFailure.ifBlank { "RTSP connection ended" }
                })
            }
        }
    }
    private class Assembler(
        val emit:(ByteArray,Long)->Unit,
        val lost:AtomicLong,
        val meter:RtpReceiverDiagnostics,
        val discarded:AtomicLong
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
            if (out.size()>0) {
                if (!broken) emit(out.toByteArray(),ts)
                else discarded.incrementAndGet()
            }
            out.reset();broken=false
        }
        fun accept(p:ByteArray) {
            meter.observe(p)
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
    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false,true)) return
        alive.set(false)
        try { socket?.close() } catch (_:Exception) {}
        udpPorts?.close()
        udpPorts = null
        udpThread?.interrupt()
        thread?.interrupt()
        feeder?.interrupt()
        frames.clear()
        input.clear()
        val oldCodec=codec
        codec=null
        val oldThread=codecThread
        codecThread=null
        Thread({
            try { oldCodec?.stop() } catch (_:Exception) {}
            try { oldCodec?.release() } catch (_:Exception) {}
            oldThread?.quitSafely()
        },"TTX-CodecClose").start()
    }

    override fun close() {
        explicitlyClosed.set(true)
        cleanup()
    }
}
