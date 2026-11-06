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
import java.util.concurrent.CountDownLatch
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
    private val latencyPolicy: DirectLatencyPolicy = DirectLatencyPolicy.SMOOTH,
    private val yuvPreference: OffscreenCodecSelection.Preference =
        OffscreenCodecSelection.Preference.SOFTWARE_FIRST
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
        val staleDecodedSuppressed: Long,
        val rtpAuClock: RtpAuClockAudit.Snapshot,
        val surfaceTimeline: SurfaceFrameTimeline.Snapshot,
        val streamCadence: StreamCadenceAudit.Snapshot,
        val streamHealth: DirectStreamHealth.Snapshot,
        val gopFence: PreviewGopEpochFence.Snapshot,
        val foreignPayloadPackets: Long,
        val malformedRtpPackets: Long
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
    private val surfaceTimeline = SurfaceFrameTimeline()
    private val rtpAuClock = RtpAuClockAudit()
    private val streamCadence = StreamCadenceAudit()
    private val streamHealth = DirectStreamHealth()
    private val gopFence = PreviewGopEpochFence()
    private val codecReleased = CountDownLatch(1)
    private val queuePeak = AtomicLong()
    private val keepAliveSent = AtomicLong()
    private val keepAliveOk = AtomicLong()
    private val keepAliveUnsupported = AtomicLong()
    private val udpPackets = AtomicLong()
    private val rtpDiagnostics = RtpReceiverDiagnostics()
    @Volatile private var selectedRtpPayload: RtpSelectedPayloadGate? = null
    private val discardedAccessUnits = AtomicLong()
    private val rtcpSent = AtomicLong()
    private val rtcpReporterSsrc = SecureRandom().nextInt()
    private val rtcpTracker = RtcpReceiverReport.Tracker()
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
        staleDecodedSuppressed.get(),
        rtpAuClock.snapshot(),
        surfaceTimeline.snapshot(),
        streamCadence.snapshot(SystemClock.elapsedRealtime()),
        streamHealth.snapshot(SystemClock.elapsedRealtime()),
        gopFence.snapshot(),
        selectedRtpPayload?.metrics()?.foreignPayload ?: 0L,
        selectedRtpPayload?.metrics()?.malformed ?: 0L
    )
    /**
     * Called ONLY by the detached RTSP recovery worker. Never block
     * MainActivity, Surface callbacks or MediaCodec's callback handler.
     */
    fun awaitCodecRelease(timeoutMs: Long): Boolean {
        require(timeoutMs in 1L..30_000L)
        return codecReleased.await(timeoutMs,TimeUnit.MILLISECONDS)
    }

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
    private fun createDecoder(sdp: String) {
        val csd = H264SdpParameterSets.fromSdp(sdp)
        val pair = csd.sps to csd.pps
        status("H264 SDP video PT=" + (csd.payloadType?.toString() ?: "unknown") +
            if (csd.sps == null || csd.pps == null)
                " · no validated SPS/PPS; codec receives in-band headers only"
            else " · validated SPS/PPS")
        val dimensions = H264SpsDimensions.configuredSize(pair.first)
        val fmt = MediaFormat.createVideoFormat(
            "video/avc", dimensions.width, dimensions.height)
        status("MediaCodec A500S SPS size=" + dimensions.width +
            "x" + dimensions.height +
            if (H264SpsDimensions.parse(pair.first) == null)
                " (640x480 fallback; SPS missing or invalid)" else " (from SPS)")
        // Secondary decoder is lazy: native resources are allocated only
        // after an explicit user opt-in AND the next H264 IDR frame.
        yuvSidecar = onDecodedYuv?.let { frameConsumer ->
            H264YuvSidecar(
                pair.first, pair.second, frameConsumer,
                onFault = { message -> status(message) },
                onDiscontinuity = { onYuvDiscontinuity?.invoke() },
                decoderPreference = yuvPreference
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
        // This is a *local codec event*, not physical screen scanout.
        // MediaCodec renders into Surface asynchronously after
        // releaseOutputBuffer(index, true); correlate by original PTS.
        decoder.setOnFrameRenderedListener({ _, ptsUs, renderNanoTime ->
            if (alive.get() &&
                surfaceTimeline.rendered(ptsUs,renderNanoTime) != null) {
                // The callback's native nanoseconds are not elapsedRealtime
                // and must not be mixed with RTP-receipt timestamps.
                streamCadence.observe(
                    StreamCadenceAudit.Stage.RENDER_CALLBACK,
                    ptsUs,SystemClock.elapsedRealtime())
            }
        },codecHandler)
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
                    if (info.size > 0) streamHealth.onDecoded(now)
                    val decodedAge = if (info.size > 0) {
                        streamCadence.observe(
                            StreamCadenceAudit.Stage.CODEC_OUTPUT,
                            info.presentationTimeUs,now)
                        outputTimeline.emitted(info.presentationTimeUs,now)
                    } else null
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
                        surfaceTimeline.released(info.presentationTimeUs,
                            System.nanoTime())
                        try {
                            c.releaseOutputBuffer(i, true)
                        } catch (failure: Exception) {
                            surfaceTimeline.forget(info.presentationTimeUs)
                            throw failure
                        }
                        rendered.incrementAndGet()
                        val submittedAtMs=SystemClock.elapsedRealtime()
                        streamCadence.observe(
                            StreamCadenceAudit.Stage.SURFACE_RELEASE,
                            info.presentationTimeUs,submittedAtMs)
                        streamHealth.onSurfaceReleased(submittedAtMs)
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
                    val inputReadyAt = SystemClock.elapsedRealtime()
                    inputWaitMs.addAndGet(
                        (inputReadyAt-waitStart).coerceAtLeast(0L))
                    if (!alive.get() || index == null) break
                    // The callback may provide a slot just AFTER the wait
                    // budget; enforce the deadline on success, not only on
                    // empty polls. Reconnect rather than drop a reference AU.
                    if (latencyPolicy.inputWaitExceeded(waitStart,inputReadyAt)) {
                        bufferAgeRestarts.incrementAndGet()
                        throw IllegalStateException(
                            "Codec input callback arrived after " +
                                latencyPolicy.maxInputWaitMs + "ms")
                    }
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
                    streamCadence.observe(
                        StreamCadenceAudit.Stage.CODEC_INPUT,
                        sample.timestampUs,SystemClock.elapsedRealtime())
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
        if (!gopFence.mayQueue())
            throw IllegalStateException(
                "Preview H264 reference epoch revoked; MediaCodec reconnect required")
        // Mark AU receipt BEFORE any preview queue backpressure. Otherwise a
        // frame that waited 900ms would be stamped as newly captured for AI.
        val receivedAtMs = SystemClock.elapsedRealtime()
        val stamp=rtpAuClock.observe(ts,receivedAtMs)
        if (!stamp.acceptable || stamp.presentationTimeUs == null) {
            // This is a source 90kHz RTP discontinuity. The decoder must
            // recover from a new IDR: never fabricate a smooth PTS sequence.
            bufferAgeRestarts.incrementAndGet()
            throw IllegalStateException(
                "A500S RTP AU clock discontinuity: " + stamp.status +
                    " (reconnect, await new H264 IDR)")
        }
        val sample=Sample(bytes,stamp.presentationTimeUs,receivedAtMs)
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
        // Accepted the AU into the preview queue. Even if the feeder has
        // not dequeued it yet, all later GOP damage revokes this decoder.
        gopFence.queued()
        receivedAccessUnits.incrementAndGet()
        streamCadence.observe(
            StreamCadenceAudit.Stage.AU_RECEIVED,
            sample.timestampUs,receivedAtMs)
        if (!streamHealth.onAu(sample.timestampUs,receivedAtMs))
            throw IllegalStateException("Invalid A500S AU timeline")
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
            // V1.3.27 SDP chose the codec CSD's video PT; V1.3.28
            // applies the SAME PT to the actual RTP data before UDP reorder
            // or H264 assembly. Foreign PT must not revoke a valid GOP.
            val payloadGate = RtpSelectedPayloadGate(
                H264SdpParameterSets.fromSdp(describe.sdp).payloadType)
            selectedRtpPayload = payloadGate
            status("RTP video payload PT=" +
                (payloadGate.expectedPayloadType?.toString() ?: "unknown") +
                " · reject foreign PT before MediaCodec")
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
            val playStartedMs = SystemClock.elapsedRealtime()
            check(streamHealth.start(playStartedMs))
            status("RTSP PLAY 200 · session timeout="+timeoutSecs+
                "s · keepalive mỗi "+intervalMs/1000+"s")
            createDecoder(describe.sdp)
            val assemble = RtpH264AccessUnitAssembler(
                emit = ::enqueue,
                onDamaged = {
                    // An offscreen decoder can reset its own GOP, but the
                    // Surface preview still owns queued/in-flight P pictures.
                    val verdict = gopFence.damaged()
                    if (verdict ==
                        PreviewGopEpochFence.Recovery.RECONNECT_PREVIEW) {
                        lastNetworkFailure =
                            "RTP H264 GOP lost after preview input; " +
                            "reconnect Surface MediaCodec from fresh IDR"
                        // Gate future native output callbacks before tearing
                        // down the network/MediaCodec session.
                        alive.set(false)
                        try { socket?.close() } catch (_: Exception) {}
                    }
                    // Do not allow an old RAW inference or YUV fixture to
                    // survive an RTP discontinuity even when the sidecar
                    // decoder was not enabled by the owner.
                    try { onYuvDiscontinuity?.invoke() }
                    catch (_: Exception) {}
                    yuvSidecar?.dropPredictionChain()
                    if (verdict ==
                        PreviewGopEpochFence.Recovery.RECONNECT_PREVIEW)
                        throw IllegalStateException(lastNetworkFailure)
                },
                onPacket = { rtpDiagnostics.observe(it) },
                onLostSequence = { dropped.incrementAndGet() },
                onDiscarded = { discardedAccessUnits.incrementAndGet() }
            )
            if (!useUdp) earlyMedia.forEach { rtp ->
                if (payloadGate.classify(rtp) !=
                    RtpSelectedPayloadGate.Verdict.FOREIGN_PAYLOAD) {
                    streamHealth.onRtp(SystemClock.elapsedRealtime())
                    assemble.accept(rtp)
                }
            }

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
                                    val data = packet.data.copyOfRange(packet.offset,
                                        packet.offset + packet.length)
                                    if (payloadGate.classify(data) !=
                                        RtpSelectedPayloadGate.Verdict.FOREIGN_PAYLOAD) {
                                        val packetAt=SystemClock.elapsedRealtime()
                                        lastRtpAt.set(packetAt)
                                        streamHealth.onRtp(packetAt)
                                        udpPackets.incrementAndGet()
                                        reorder.accept(data,packetAt,deliver)
                                    }
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
                val health = streamHealth.snapshot(now)
                if (health.requiresReconnect)
                    error("Direct video frozen: " + health.status +
                        " (RTP " + health.lastRtpAgoMs +
                        "ms, AU " + health.lastAuAgoMs +
                        "ms, codec " + health.lastDecodeAgoMs +
                        "ms, Surface " + health.lastSurfaceAgoMs + "ms)")
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
                    val report = rtcpTracker.prepare(
                        rtcpReporterSsrc, rtpDiagnostics.snapshot())
                    if (report != null && report.bytes.isNotEmpty()) {
                        try {
                            val feedback = report.bytes
                            if (useUdp && serverRtcpPort != null) {
                                val rtcp = udpPorts?.rtcp
                                    ?: error("RTCP UDP port unavailable")
                                rtcp.send(DatagramPacket(feedback, feedback.size,
                                    InetAddress.getByName(host), serverRtcpPort))
                                rtcpTracker.markSent(report)
                                rtcpSent.incrementAndGet()
                            } else if (!useUdp && rtcpChannel in 0..255) {
                                val header = byteArrayOf(0x24, rtcpChannel.toByte(),
                                    (feedback.size ushr 8).toByte(), feedback.size.toByte())
                                dst.write(header)
                                dst.write(feedback)
                                dst.flush()
                                rtcpTracker.markSent(report)
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
                        if (packet.channel == rtpChannel &&
                            payloadGate.classify(packet.payload) !=
                                RtpSelectedPayloadGate.Verdict.FOREIGN_PAYLOAD) {
                            val packetAt=SystemClock.elapsedRealtime()
                            lastRtpAt.set(packetAt)
                            streamHealth.onRtp(packetAt)
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
        surfaceTimeline.clear()
        yuvSidecar?.close()
        yuvSidecar = null
        val oldCodec=codec
        codec=null
        val oldThread=codecThread
        codecThread=null
        Thread({
            try {
                try { oldCodec?.stop() } catch (_:Exception) {}
                try { oldCodec?.release() } catch (_:Exception) {}
            } finally {
                try { oldThread?.quitSafely() } catch (_:Exception) {}
                codecReleased.countDown()
            }
        },"TTX-CodecClose").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        explicitlyClosed.set(true)
        cleanup()
    }
}
