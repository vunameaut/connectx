package com.vhn.connectx.net

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.vhn.connectx.BuildConfig
import org.webrtc.*

/**
 * WebRTC Media Engine for Real-Time Audio, Video & Screen Sharing.
 * Supports:
 * 1. Bi-directional 2-way Voice (AudioTrack & AudioSource via Software AEC/NS)
 * 2. Camera Video Stream (Camera2Enumerator)
 * 3. OS Screen Capture (ScreenCapturerAndroid via MediaProjection)
 * 4. P2P ICE + Metered / Public Google STUN/TURN fallback
 */
class WebRtcManager(
    private val context: Context,
    private val callId: String,
    private val isCaller: Boolean
) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val AUDIO_TRACK_ID = "connectx_audio_track"
        private const val VIDEO_TRACK_ID = "connectx_video_track"
    }

    private val eglBase: EglBase = EglBase.create()
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var screenCapturer: VideoCapturer? = null
    private var screenVideoSource: VideoSource? = null
    private var screenVideoTrack: VideoTrack? = null

    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    var onRemoteVideoTrackReady: ((VideoTrack) -> Unit)? = null
    var onCallDisconnected: (() -> Unit)? = null
    var onIceConnected: (() -> Unit)? = null
    var onIceFailed: ((String) -> Unit)? = null

    // Diagnostics: which kinds of ICE candidates were gathered locally (host / srflx / relay)
    private val gatheredCandidateTypes = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private var audioManager: AudioManager? = null

    init {
        initPeerConnectionFactory()
        setupAudio()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // enableH264HighProfile = false to prevent MediaCodec crashes on Exynos/MediaTek chipsets
        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun setupAudio() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = true

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, localAudioSource)
        localAudioTrack?.setEnabled(true)
    }

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    private var isOfferProcessed = false
    private var isAnswerProcessed = false

    /**
     * Khởi tạo Local Camera
     */
    fun startCamera(localRenderer: SurfaceViewRenderer, isFront: Boolean = true) {
        // Camera may already be running (caller starts preview while ringing)
        if (cameraCapturer != null && localVideoTrack != null) {
            Log.i(TAG, "startCamera ignored: camera already running")
            return
        }
        try {
            localRenderer.init(eglContext, null)
        } catch (e: Exception) {
            Log.w(TAG, "localRenderer already initialized: ${e.message}")
        }
        localRenderer.setMirror(isFront)

        cameraCapturer = createCameraCapturer(isFront)
        if (cameraCapturer != null) {
            try {
                surfaceTextureHelper = SurfaceTextureHelper.create("CameraTextureHelper", eglContext)
                localVideoSource = peerConnectionFactory?.createVideoSource(cameraCapturer!!.isScreencast)
                cameraCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
                try {
                    cameraCapturer?.startCapture(640, 480, 24)
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback to 480x360@20fps capture: ${e.message}")
                    try {
                        cameraCapturer?.startCapture(480, 360, 20)
                    } catch (e2: Exception) {
                        Log.e(TAG, "startCapture failed: ${e2.message}")
                    }
                }

                localVideoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, localVideoSource)
                localVideoTrack?.setEnabled(true)
                localVideoTrack?.addSink(localRenderer)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing local camera track: ${e.message}", e)
            }
        }
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Find front camera first
        for (deviceName in deviceNames) {
            if (if (isFront) enumerator.isFrontFacing(deviceName) else enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }
        // Fallback
        for (deviceName in deviceNames) {
            val capturer = enumerator.createCapturer(deviceName, null)
            if (capturer != null) return capturer
        }
        return null
    }

    private fun getTurnUrls(): List<String> {
        val configUrls = BuildConfig.TURN_URLS.trim()
        val raw = if (configUrls.isNotBlank()) configUrls else "turn:global.relay.metered.ca:80,turn:global.relay.metered.ca:80?transport=tcp,turn:global.relay.metered.ca:443,turns:global.relay.metered.ca:443?transport=tcp"
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun getTurnUsername(): String {
        val u = BuildConfig.TURN_USERNAME.trim()
        return if (u.isNotBlank()) u else "dbc395ca293461527dec624b"
    }

    private fun getTurnCredential(): String {
        val c = BuildConfig.TURN_CREDENTIAL.trim()
        return if (c.isNotBlank()) c else "EbFVNsUyyCAOzA+y"
    }

    private fun hasTurnConfigured(): Boolean = true

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        val urls = getTurnUrls()
        val username = getTurnUsername()
        val password = getTurnCredential()
        servers.add(
            PeerConnection.IceServer.builder(urls)
                .setUsername(username)
                .setPassword(password)
                .createIceServer()
        )
        Log.i(TAG, "WebRTC configured with ${urls.size} TURN relay server(s) for P2P/emulator traversal")
        return servers
    }

    /**
     * Bắt đầu PeerConnection và kết nối
     */
    fun startPeerConnection(remoteRenderer: SurfaceViewRenderer) {
        if (peerConnection != null) {
            Log.i(TAG, "startPeerConnection ignored: already started")
            return
        }
        try {
            remoteRenderer.init(eglContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    Log.i(TAG, "Remote video: first frame rendered")
                    remoteVideoTrack?.let { onRemoteVideoTrackReady?.invoke(it) }
                }
                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
            })
        } catch (e: Exception) {
            Log.w(TAG, "remoteRenderer already initialized: ${e.message}")
        }

        val iceServers = buildIceServers()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && !candidate.sdp.isNullOrBlank()) {
                    val type = Regex(" typ (\\w+)").find(candidate.sdp)?.groupValues?.get(1) ?: "?"
                    gatheredCandidateTypes.add(type)
                    Log.d(TAG, "Local ICE candidate [$type]: ${candidate.sdp}")
                    val myRole = if (isCaller) "CALLER" else "CALLEE"
                    FirebaseSyncManager.sendIceCandidate(
                        callId = callId,
                        senderRole = myRole,
                        sdpMid = candidate.sdpMid ?: "0",
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteVideoTrack?.addSink(remoteRenderer)
                    onRemoteVideoTrackReady?.invoke(track)
                } else if (track is AudioTrack) {
                    remoteAudioTrack = track
                    remoteAudioTrack?.setEnabled(true)
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                if (stream != null) {
                    if (stream.videoTracks.isNotEmpty() && remoteVideoTrack == null) {
                        val track = stream.videoTracks[0]
                        remoteVideoTrack = track
                        remoteVideoTrack?.addSink(remoteRenderer)
                        onRemoteVideoTrackReady?.invoke(track)
                    }
                    if (stream.audioTracks.isNotEmpty() && remoteAudioTrack == null) {
                        remoteAudioTrack = stream.audioTracks[0]
                        remoteAudioTrack?.setEnabled(true)
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $newState (local candidate types=$gatheredCandidateTypes)")
                // Không ngắt cuộc gọi ngay khi DISCONNECTED để cho phép WebRTC tự động tái kết nối
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        Log.i(TAG, "WebRTC media channel connected successfully")
                        onIceConnected?.invoke()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "WebRTC ICE temporarily disconnected, maintaining call session...")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        val reason = if (!hasTurnConfigured()) {
                            "Không kết nối được hình ảnh: mạng của 2 máy chặn kết nối trực tiếp và app chưa cấu hình máy chủ TURN."
                        } else if (!gatheredCandidateTypes.contains("relay")) {
                            "Không kết nối được hình ảnh: máy chủ TURN không phản hồi (sai thông tin hoặc bị chặn mạng)."
                        } else {
                            "Không kết nối được hình ảnh: mạng không ổn định, hãy thử gọi lại."
                        }
                        Log.e(TAG, reason)
                        onIceFailed?.invoke(reason)
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        onCallDisconnected?.invoke()
                    }
                    else -> {}
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        // Add local tracks
        val mediaStreamLabels = listOf("connectx_stream")
        localAudioTrack?.let {
            peerConnection?.addTrack(it, mediaStreamLabels)
        }
        localVideoTrack?.let {
            peerConnection?.addTrack(it, mediaStreamLabels)
        }

        // Setup candidate listener from partner
        val partnerRole = if (isCaller) "CALLEE" else "CALLER"
        FirebaseSyncManager.listenRemoteIceCandidates(callId, partnerRole) { sdpMid, sdpMLineIndex, candidateStr ->
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
            synchronized(pendingIceCandidates) {
                if (isRemoteDescriptionSet && peerConnection != null) {
                    peerConnection?.addIceCandidate(iceCandidate)
                } else {
                    pendingIceCandidates.add(iceCandidate)
                }
            }
        }

        if (isCaller) {
            createAndSendOffer()
        } else {
            // Callee listens for Offer
            FirebaseSyncManager.listenCallSdp(callId, "offer") { offerSdp ->
                handleRemoteOffer(offerSdp)
            }
        }
    }

    private fun drainPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            isRemoteDescriptionSet = true
            for (candidate in pendingIceCandidates) {
                peerConnection?.addIceCandidate(candidate)
            }
            pendingIceCandidates.clear()
        }
    }

    private fun createAndSendOffer() {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                FirebaseSyncManager.sendCallSdp(callId, "offer", desc.description)

                // Caller listens for Answer
                FirebaseSyncManager.listenCallSdp(callId, "answer") { answerSdp ->
                    if (isAnswerProcessed) return@listenCallSdp
                    isAnswerProcessed = true
                    val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                    peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            Log.i(TAG, "Caller setRemoteDescription (Answer) successful")
                            drainPendingIceCandidates()
                        }
                    }, remoteDesc)
                }
            }
        }, sdpConstraints)
    }

    private fun handleRemoteOffer(offerSdp: String) {
        if (isOfferProcessed) return
        isOfferProcessed = true
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.i(TAG, "Callee setRemoteDescription (Offer) successful")
                drainPendingIceCandidates()
                createAndSendAnswer()
            }
        }, remoteDesc)
    }

    private fun createAndSendAnswer() {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                FirebaseSyncManager.sendCallSdp(callId, "answer", desc.description)
            }
        }, sdpConstraints)
    }

    /**
     * Bật/Tắt Micro
     */
    fun toggleMicrophone(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    /**
     * Bật/Tắt Camera
     */
    fun toggleCamera(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    /**
     * Bắt đầu Screen Share qua WebRTC
     */
    fun startScreenShare(resultCode: Int, data: Intent) {
        val permissionIntentCallback = object : MediaProjection.Callback() {}
        screenCapturer = ScreenCapturerAndroid(data, permissionIntentCallback)

        val helper = SurfaceTextureHelper.create("ScreenCaptureHelper", eglContext)
        screenVideoSource = peerConnectionFactory?.createVideoSource(true)
        screenCapturer?.initialize(helper, context, screenVideoSource?.capturerObserver)
        screenCapturer?.startCapture(1280, 720, 20)

        screenVideoTrack = peerConnectionFactory?.createVideoTrack("connectx_screen_track", screenVideoSource)
        screenVideoTrack?.setEnabled(true)

        // Replace video track with screen track if sender exists, or add it
        val videoSender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
            ?: peerConnection?.transceivers?.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender

        if (videoSender != null) {
            videoSender.setTrack(screenVideoTrack, false)
        } else {
            peerConnection?.addTrack(screenVideoTrack, listOf("connectx_stream"))
            if (isCaller) {
                createAndSendOffer()
            }
        }
    }

    /**
     * Dừng Screen Share qua WebRTC và khôi phục camera
     */
    fun stopScreenShare() {
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null
            screenVideoSource?.dispose()
            screenVideoSource = null

            // Restore camera track
            val videoSender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
                ?: peerConnection?.transceivers?.find { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender
            videoSender?.setTrack(localVideoTrack, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen share: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            cameraCapturer?.stopCapture()
            cameraCapturer?.dispose()
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()

            localAudioTrack?.dispose()
            localVideoTrack?.dispose()
            screenVideoTrack?.dispose()

            localAudioSource?.dispose()
            localVideoSource?.dispose()
            screenVideoSource?.dispose()

            peerConnection?.close()
            peerConnection = null

            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            surfaceTextureHelper?.dispose()
            eglBase.release()

            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebRtcManager cleanup: ${e.message}")
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SdpObserver onCreateFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SdpObserver onSetFailure: $error")
        }
    }
}
