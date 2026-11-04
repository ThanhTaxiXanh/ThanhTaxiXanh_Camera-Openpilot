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
import java.util.concurrent.atomic.AtomicInteger

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
    private val preferUdp: Boolean = false,
    private val onDecodedYuv: ((Yuv420FrameConverter.Frame, Long) -> Unit)? = null,
    private val onYuvDiscontinuity: (() -> Unit)? = null,
    private val latencyPolicy: DirectLatencyPolicy = DirectLatencyPolicy.SMOOTH
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
        val rtcpSent: Long, val discardedAccessUnits: Long,
        val udpReordered: Long, val udpRecovered: Long, val udpSkipped: Long,
        val sidecar: H264YuvSidecar.Metrics?,
        val maxAccessUnitQueue: Int,
        val oldestQueueAgeMs: Long?,
        val decodeTimeline: PreviewDecodeTimeline.Snapshot,
        val bufferAgeRestarts: Long,
        val staleDecodedSuppressed: Long
    )
    private data class Sample(
        val data: ByteArray, val timestampUs: Long, val receivedAtMs: Long
    )
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
    private val bufferAgeRestarts = AtomicLong()
    private val staleDecodedSuppressed = AtomicLong()
    private val oldCodecOutputStreak = AtomicInteger()
    private val outputTimeline = PreviewDecodeTimeline()
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
    // A500S H264 P-frames reference earlier pictures. Never discard
    // arbitrary compressed frames to catch up: after a freshness failure,
    // restart the RTSP decoder session and reacquire an IDR instead.
    private val frames = ArrayBlockingQueue<Sample>(latencyPolicy.maxQueuedAccessUnits)
    private var socket: Socket? = null
    private var udpPorts: A500sUdpPortPair? = null
    private var udpThread: Thread? = null
    @Volatile private var udpReorder: RtpJitterQueue? = null
    private var codec: MediaCodec? = null
    private var yuvSidecar: H264YuvSidecar? = null
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
        rtpDiagnostics.snapshot(), rtcpSent.get(), discardedAccessUnits.get(),
        udpReorder?.outOfOrderPackets ?: 0,
        udpReorder?.recoveredOutOfOrderPackets ?: 0,
        udpReorder?.skippedMissingPackets ?: 0,
        yuvSidecar?.metrics(),
        latencyPolicy.maxQueuedAccessUnits,
        frames.peek()?.let {
            (SystemClock.elapsedRealtime() - it.receivedAtMs).coerceAtLeast(0L)
        },
        outputTimeline.snapshot(),
        bufferAgeRestarts.get(),
        staleDecodedSuppressed.get()
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
        // Secondary decoder is lazy: native resources are allocated only
        // after an explicit user opt-in AND the next H264 IDR frame.
        yuvSidecar = onDecodedYuv?.let { frameConsumer ->
            H264YuvSidecar(
                pair.first, pair.second, frameConsumer,
                onFault = { message -> status(message) },
                onDiscontinuity = { onYuvDiscontinuity?.invoke() }
            )
        }
        if (pair.first != null && pair.second != null) {
            fmt.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pair.first!!))
            fmt.setByteBuffer("csd-1",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pair.second!!))
        }
        fmt.setInteger(MediaFormat.KEY_PRIORITY,0)
        // Fastest possible decode is not the same as evenly paced display.
        // Low latency was previously forced on despite this being a smooth
        // playback mode; disable the optional vendor-specific low-latency hint.
        if (Build.VERSION.SDK_INT >= 30)
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY,
                if (latencyPolicy.requestCodecLowLatency) 1 else 0)
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
                    val decodedAge = if (info.size > 0)
                        outputTimeline.emitted(info.presentationTimeUs,now)
                    else null
                    // Surface-configured codecs cannot reliably expose
                    // YUV. The optional second offscreen codec handles it.
                    // Critical V0.9.5 change:
                    // NEVER retain a decoded hardware output buffer while
                    // waiting for a target PTS. Input frame pacing is handled
                    // on the feeder thread, preserving codec output slots.
                    if (info.size > 0 &&
                        latencyPolicy.shouldSuppressDecodedOutputAge(decodedAge)) {
                        // The decoder has ALREADY decoded this picture and
                        // maintained the H264 reference chain. Suppressing
                        // Surface output here is safe; skipping compressed
                        // P-frames before decode would not be safe.
                        c.releaseOutputBuffer(i, false)
                        staleDecodedSuppressed.incrementAndGet()
                        if (oldCodecOutputStreak.incrementAndGet() >=
                                latencyPolicy.staleOutputReconnectCount &&
                            alive.compareAndSet(true,false)) {
                            bufferAgeRestarts.incrementAndGet()
                            lastNetworkFailure =
                                "Codec output delayed >" +
                                latencyPolicy.maxOutputCallbackAgeMs +
                                "ms for " + oldCodecOutputStreak.get() +
                                " frames; reconnect from IDR"
                            status(lastNetworkFailure)
                            try { socket?.close() } catch (_: Exception) {}
                        }
                    } else if (info.size > 0) {
                        oldCodecOutputStreak.set(0)
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
                    requireFreshAu(sample,"before MediaCodec input acquisition")
                    val waitStart=SystemClock.elapsedRealtime()
                    var index:Int?=null
                    while (alive.get() && index == null) {
                        index=input.poll(40,TimeUnit.MILLISECONDS)
                        if (index == null &&
                            latencyPolicy.inputWaitExceeded(
                                waitStart,SystemClock.elapsedRealtime())) {
                            bufferAgeRestarts.incrementAndGet()
                            throw IllegalStateException(
                                "Codec input not available before " +
                                    latencyPolicy.maxInputWaitMs + "ms")
                        }
                    }
                    inputWaitMs.addAndGet(
                        (SystemClock.elapsedRealtime()-waitStart).coerceAtLeast(0L))
                    if (!alive.get() || index == null) break
                    requireFreshAu(sample,"before MediaCodec.queueInputBuffer")

                    val dst=decoder.getInputBuffer(index)
                        ?: throw IllegalStateException("MediaCodec returned a null input buffer")
                    dst.clear()
                    if (sample.data.size > dst.remaining()) {
                        throw IllegalStateException(
                            "H264 access unit exceeds decoder input buffer: "+
                            sample.data.size+" > "+dst.remaining())
                    }
                    dst.put(sample.data)
                    outputTimeline.submitted(sample.timestampUs,sample.receivedAtMs)
                    try {
                        decoder.queueInputBuffer(index,0,sample.data.size,
                            sample.timestampUs,0)
                    } catch (failure: Exception) {
                        outputTimeline.forget(sample.timestampUs)
                        throw failure
                    }
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
    private fun requireFreshAu(sample: Sample, stage: String) {
        val verdict = latencyPolicy.checkAge(
            sample.receivedAtMs,SystemClock.elapsedRealtime())
        if (verdict != DirectLatencyPolicy.AgeStatus.FRESH) {
            bufferAgeRestarts.incrementAndGet()
            throw IllegalStateException(
                "Stale H264 access unit at " + stage + ": " + verdict +
                    ", policy=" + latencyPolicy.name +
                    " (must reconnect from next IDR)")
        }
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
        // Mark AU receipt BEFORE any preview queue backpressure. Otherwise a
        // frame that waited 900ms would be stamped as newly captured for AI.
        val receivedAtMs = SystemClock.elapsedRealtime()
        val sample=Sample(bytes,ts*1000000L/90000L,receivedAtMs)
        // A growing backlog must be measured before accepting another AU.
        // Never remove only its old P-frames: restart the decoder and
        // reacquire IDR when the selected freshness policy cannot be met.
        frames.peek()?.let { requireFreshAu(it,"network AU arrival") }
        val accepted = frames.offer(
            sample,latencyPolicy.enqueueWaitMs,TimeUnit.MILLISECONDS)
        if (!accepted) {
            queueOverflows.incrementAndGet()
            throw IllegalStateException(
                "Decoder không theo kịp: hàng đợi " +
                    latencyPolicy.maxQueuedAccessUnits +
                    " AU đầy hơn " + latencyPolicy.enqueueWaitMs +
                    "ms; reconnect bảo vệ chuỗi H264 P-frame."
            )
        }
        receivedAccessUnits.incrementAndGet()
        compressedBytes.addAndGet(bytes.size.toLong())
        // Never wait for the secondary AI decoder; dropping its GOP is safe
        // because the preview retains its complete access-unit sequence.
        if (isAiEnabled()) {
            yuvSidecar?.offer(bytes, receivedAtMs)
        } else {
            yuvSidecar?.pause()
        }
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
            status("Camera chọn: " + activeTransportName +
                " · Transport: " + negotiated.take(190))
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
            val assemble=Assembler(::enqueue,dropped,rtpDiagnostics,
                discardedAccessUnits) {
                // A discarded AU can break the reference-picture chain.
                // Reset the optional AI decoder and temporal pair, never
                // the independent Surface preview.
                yuvSidecar?.dropPredictionChain()
            }
            if (!useUdp) earlyMedia.forEach { assemble.accept(it) }

            val lastRtpAt = AtomicLong(SystemClock.elapsedRealtime())
            if (useUdp) {
                val pair = udpPorts ?: error("UDP negotiation lost socket")
                val reorder = RtpJitterQueue(capacity = 32, maxDelayMs = 55L)
                udpReorder = reorder
                val t = Thread({
                    val buf = ByteArray(65536)
                    val loggedUnexpectedSender = AtomicBoolean(false)
                    val deliver:(ByteArray)->Unit = { assemble.accept(it) }
                    try {
                        while (alive.get()) {
                            try {
                                val packet = pair.receivePacket(buf)
                                // Bind to the selected Wi-Fi Network and accept
                                // video packets from the camera host only.
                                if (packet.address.hostAddress == host && packet.length >= 12) {
                                    lastRtpAt.set(SystemClock.elapsedRealtime())
                                    udpPackets.incrementAndGet()
                                    reorder.accept(
                                        packet.data.copyOfRange(packet.offset,
                                            packet.offset + packet.length),
                                        SystemClock.elapsedRealtime(), deliver)
                                } else if (packet.length >= 12) {
                                    if (loggedUnexpectedSender.compareAndSet(false, true))
                                        status("UDP gói từ địa chỉ ngoài camera: " +
                                            packet.address.hostAddress +
                                            " · đợi " + host)
                                }
                            } catch (_: java.net.SocketTimeoutException) {
                                // Release a buffered gap after a short deadline
                                // rather than keeping H264 waiting indefinitely.
                                reorder.tick(SystemClock.elapsedRealtime(), deliver)
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

            val playStartedMs = SystemClock.elapsedRealtime()
            var nextSequence = 4
            var nextRtcpAt = SystemClock.elapsedRealtime() + 5000L
            var nextKeepAliveAt = SystemClock.elapsedRealtime() + intervalMs
            var pendingSeq = -1
            var pendingAt = 0L
            var preferredMethod = "GET_PARAMETER"
            var unsupportedBoth = false
            while(alive.get()) {
                val now=SystemClock.elapsedRealtime()
                val receivedPackets = rtpDiagnostics.snapshot().packets
                if (receivedPackets == 0L && now - lastRtpAt.get() > 5000L) {
                    error("RTP timeout: PLAY 200 but zero video packets for 5 seconds; " +
                        "negotiated=" + activeTransportName)
                }
                if (receivedPackets > 0L && receivedAccessUnits.get() == 0L &&
                    now - playStartedMs > 8500L) {
                    error("RTP/H264 stalled: packets received but zero H264 access units")
                }
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
                                    // OPTIONS is a read-only RTSP capability
                                    // query. SET_PARAMETER can change camera
                                    // state and was rejected on this A500S.
                                    preferredMethod="OPTIONS"
                                    nextKeepAliveAt=SystemClock.elapsedRealtime()+1000L
                                } else {
                                    unsupportedBoth=true
                                    status("Camera từ chối GET_PARAMETER và OPTIONS; " +
                                        "tắt keepalive, không gửi lệnh thay đổi cấu hình")
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
        val discarded:AtomicLong,
        val onDamaged: () -> Unit
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
            if (broken) {
                discarded.incrementAndGet()
                onDamaged()
            } else if (out.size()>0) {
                emit(out.toByteArray(),ts)
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
        udpReorder = null
        thread?.interrupt()
        feeder?.interrupt()
        frames.clear()
        input.clear()
        outputTimeline.clear()
        yuvSidecar?.close()
        yuvSidecar = null
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
