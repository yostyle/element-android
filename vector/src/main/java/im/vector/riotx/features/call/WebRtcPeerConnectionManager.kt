/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.call

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.RequiresApi
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.extensions.tryThis
import im.vector.matrix.android.api.session.call.CallState
import im.vector.matrix.android.api.session.call.CallsListener
import im.vector.matrix.android.api.session.call.EglUtils
import im.vector.matrix.android.api.session.call.MxCall
import im.vector.matrix.android.api.session.call.TurnServerResponse
import im.vector.matrix.android.api.session.room.model.call.CallAnswerContent
import im.vector.matrix.android.api.session.room.model.call.CallCandidatesContent
import im.vector.matrix.android.api.session.room.model.call.CallHangupContent
import im.vector.matrix.android.api.session.room.model.call.CallInviteContent
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.core.services.CallService
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage peerConnectionFactory & Peer connections outside of activity lifecycle to resist configuration changes
 * Use app context
 */
@Singleton
class WebRtcPeerConnectionManager @Inject constructor(
        private val context: Context,
        private val sessionHolder: ActiveSessionHolder
) : CallsListener {

    interface CurrentCallListener {
        fun onCurrentCallChange(call: MxCall?)
        fun onCaptureStateChanged(captureInError: Boolean)
    }

    private val currentCallsListeners = emptyList<CurrentCallListener>().toMutableList()
    fun addCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.add(listener)
    }

    fun removeCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.remove(listener)
    }

    val audioManager = CallAudioManager(context.applicationContext)

    data class CallContext(
            val mxCall: MxCall,

            var peerConnection: PeerConnection? = null,

            var localMediaStream: MediaStream? = null,
            var remoteMediaStream: MediaStream? = null,

            var localAudioSource: AudioSource? = null,
            var localAudioTrack: AudioTrack? = null,

            var localVideoSource: VideoSource? = null,
            var localVideoTrack: VideoTrack? = null,

            var remoteVideoTrack: VideoTrack? = null
    ) {

        var offerSdp: CallInviteContent.Offer? = null

        val iceCandidateSource: PublishSubject<IceCandidate> = PublishSubject.create()
        private val iceCandidateDisposable = iceCandidateSource
                .buffer(300, TimeUnit.MILLISECONDS)
                .subscribe {
                    // omit empty :/
                    if (it.isNotEmpty()) {
                        Timber.v("## Sending local ice candidates to call")
                        // it.forEach { peerConnection?.addIceCandidate(it) }
                        mxCall.sendLocalIceCandidates(it)
                    }
                }

        var remoteCandidateSource: ReplaySubject<IceCandidate>? = null
        var remoteIceCandidateDisposable: Disposable? = null

        // We register an availability callback if we loose access to camera
        var cameraAvailabilityCallback: CameraRestarter? = null

        fun release() {
            remoteIceCandidateDisposable?.dispose()
            iceCandidateDisposable?.dispose()

            peerConnection?.close()
            peerConnection?.dispose()

            localAudioSource?.dispose()
            localVideoSource?.dispose()

            localAudioSource = null
            localAudioTrack = null
            localVideoSource = null
            localVideoTrack = null
            localMediaStream = null
            remoteMediaStream = null
        }
    }

//    var localMediaStream: MediaStream? = null

    private val executor = Executors.newSingleThreadExecutor()

    private val rootEglBase by lazy { EglUtils.rootEglBase }

    private var peerConnectionFactory: PeerConnectionFactory? = null

//    private var localSdp: SessionDescription? = null

    private var videoCapturer: VideoCapturer? = null

    var capturerIsInError = false
        set(value) {
            field = value
            currentCallsListeners.forEach {
                tryThis { it.onCaptureStateChanged(value) }
            }
        }

    var localSurfaceRenderer: WeakReference<SurfaceViewRenderer>? = null
    var remoteSurfaceRenderer: WeakReference<SurfaceViewRenderer>? = null

    var currentCall: CallContext? = null
        set(value) {
            field = value
            currentCallsListeners.forEach {
                tryThis { it.onCurrentCallChange(value?.mxCall) }
            }
        }

    init {
        // TODO do this lazyly
        executor.execute {
            createPeerConnectionFactory()
        }
    }

    private fun createPeerConnectionFactory() {
        if (peerConnectionFactory != null) return
        Timber.v("## VOIP createPeerConnectionFactory")
        val eglBaseContext = rootEglBase?.eglBaseContext ?: return Unit.also {
            Timber.e("## VOIP No EGL BASE")
        }

        Timber.v("## VOIP PeerConnectionFactory.initialize")
        PeerConnectionFactory.initialize(PeerConnectionFactory
                .InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                eglBaseContext,
                /* enableIntelVp8Encoder */
                true,
                /* enableH264HighProfile */
                true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        Timber.v("## VOIP PeerConnectionFactory.createPeerConnectionFactory ...")
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()

        // attachViewRenderersInternal()
    }

    private fun createPeerConnection(callContext: CallContext, turnServerResponse: TurnServerResponse?) {
        val iceServers = mutableListOf<PeerConnection.IceServer>().apply {
            turnServerResponse?.let { server ->
                server.uris?.forEach { uri ->
                    add(
                            PeerConnection
                                    .IceServer
                                    .builder(uri)
                                    .setUsername(server.username)
                                    .setPassword(server.password)
                                    .createIceServer()
                    )
                }
            }
        }
        Timber.v("## VOIP creating peer connection...with iceServers $iceServers ")
        callContext.peerConnection = peerConnectionFactory?.createPeerConnection(iceServers, StreamObserver(callContext))
    }

    private fun sendSdpOffer(callContext: CallContext) {
        val constraints = MediaConstraints()
        // These are deprecated options
//        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (currentCall?.mxCall?.isVideoCall == true) "true" else "false"))

        Timber.v("## VOIP creating offer...")
        callContext.peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                if (p0 == null) return
//                localSdp = p0
                callContext.peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, p0)
                // send offer to peer
                currentCall?.mxCall?.offerSdp(p0)
            }
        }, constraints)
    }

    private fun getTurnServer(callback: ((TurnServerResponse?) -> Unit)) {
        sessionHolder.getActiveSession().callSignalingService().getTurnServer(object : MatrixCallback<TurnServerResponse?> {
            override fun onSuccess(data: TurnServerResponse?) {
                callback(data)
            }

            override fun onFailure(failure: Throwable) {
                callback(null)
            }
        })
    }

    fun attachViewRenderers(localViewRenderer: SurfaceViewRenderer, remoteViewRenderer: SurfaceViewRenderer, mode: String?) {
        Timber.v("## VOIP attachViewRenderers localRendeder $localViewRenderer / $remoteViewRenderer")
        this.localSurfaceRenderer = WeakReference(localViewRenderer)
        this.remoteSurfaceRenderer = WeakReference(remoteViewRenderer)
        getTurnServer { turnServer ->
            val call = currentCall ?: return@getTurnServer
            when (mode) {
                VectorCallActivity.INCOMING_ACCEPT  -> {
                    internalAcceptIncomingCall(call, turnServer)
                }
                VectorCallActivity.INCOMING_RINGING -> {
                    // wait until accepted to create peer connection
                    // TODO eventually we could already display local stream in PIP?
                }
                VectorCallActivity.OUTGOING_CREATED -> {
                    executor.execute {
                        // 1. Create RTCPeerConnection
                        createPeerConnection(call, turnServer)

                        // 2. Access camera (if video call) + microphone, create local stream
                        createLocalStream(call)

                        // 3. add local stream
                        call.localMediaStream?.let { call.peerConnection?.addStream(it) }
                        attachViewRenderersInternal()

                        // create an offer, set local description and send via signaling
                        sendSdpOffer(call)

                        Timber.v("## VOIP remoteCandidateSource ${call.remoteCandidateSource}")
                        call.remoteIceCandidateDisposable = call.remoteCandidateSource?.subscribe({
                            Timber.v("## VOIP adding remote ice candidate $it")
                            call.peerConnection?.addIceCandidate(it)
                        }, {
                            Timber.v("## VOIP failed to add remote ice candidate $it")
                        })
                    }
                }
                else                                -> {
                    // Fallback for old android, try to restart capture when attached
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && capturerIsInError && call.mxCall.isVideoCall) {
                        // try to restart capture?
                        videoCapturer?.startCapture(1280, 720, 30)
                    }
                    // sink existing tracks (configuration change, e.g screen rotation)
                    attachViewRenderersInternal()
                }
            }
        }
    }

    private fun internalAcceptIncomingCall(callContext: CallContext, turnServerResponse: TurnServerResponse?) {
        executor.execute {
            // 1) create peer connection
            createPeerConnection(callContext, turnServerResponse)

            // create sdp using offer, and set remote description
            // the offer has beed stored when invite was received
            callContext.offerSdp?.sdp?.let {
                SessionDescription(SessionDescription.Type.OFFER, it)
            }?.let {
                callContext.peerConnection?.setRemoteDescription(SdpObserverAdapter(), it)
            }
            // 2) Access camera + microphone, create local stream
            createLocalStream(callContext)

            // 2) add local stream
            currentCall?.localMediaStream?.let { callContext.peerConnection?.addStream(it) }
            attachViewRenderersInternal()

            // create a answer, set local description and send via signaling
            createAnswer()

            Timber.v("## VOIP remoteCandidateSource ${callContext.remoteCandidateSource}")
            callContext.remoteIceCandidateDisposable = callContext.remoteCandidateSource?.subscribe({
                Timber.v("## VOIP adding remote ice candidate $it")
                callContext.peerConnection?.addIceCandidate(it)
            }, {
                Timber.v("## VOIP failed to add remote ice candidate $it")
            })
        }
    }

    private fun createLocalStream(callContext: CallContext) {
        if (callContext.localMediaStream != null) {
            Timber.e("## VOIP localMediaStream already created")
            return
        }
        if (peerConnectionFactory == null) {
            Timber.e("## VOIP peerConnectionFactory is null")
            return
        }
        val audioSource = peerConnectionFactory!!.createAudioSource(DEFAULT_AUDIO_CONSTRAINTS)
        val localAudioTrack = peerConnectionFactory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(true)

        callContext.localAudioSource = audioSource
        callContext.localAudioTrack = localAudioTrack

        val localMediaStream = peerConnectionFactory!!.createLocalMediaStream("ARDAMS") // magic value?

        // Add audio track
        localMediaStream?.addTrack(localAudioTrack)

        callContext.localMediaStream = localMediaStream

        // add video track if needed
        if (callContext.mxCall.isVideoCall) {
            val cameraIterator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
            val frontCamera = cameraIterator.deviceNames
                    ?.firstOrNull { cameraIterator.isFrontFacing(it) }
                    ?: cameraIterator.deviceNames?.first()

            // TODO detect when no camera or no front camera

            val videoCapturer = cameraIterator.createCapturer(frontCamera, object : CameraEventsHandlerAdapter() {
                override fun onFirstFrameAvailable() {
                    super.onFirstFrameAvailable()
                    capturerIsInError = false
                }

                override fun onCameraClosed() {
                    // This could happen if you open the camera app in chat
                    // We then register in order to restart capture as soon as the camera is available again
                    Timber.v("## VOIP onCameraClosed")
                    this@WebRtcPeerConnectionManager.capturerIsInError = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val restarter = CameraRestarter(frontCamera ?: "", callContext.mxCall.callId)
                        callContext.cameraAvailabilityCallback = restarter
                        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        cameraManager.registerAvailabilityCallback(restarter, null)
                    }
                }
            })

            val videoSource = peerConnectionFactory!!.createVideoSource(videoCapturer.isScreencast)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
            Timber.v("## VOIP Local video source created")

            videoCapturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource!!.capturerObserver)
            // HD
            videoCapturer.startCapture(1280, 720, 30)

            this.videoCapturer = videoCapturer

            val localVideoTrack = peerConnectionFactory!!.createVideoTrack("ARDAMSv0", videoSource)
            Timber.v("## VOIP Local video track created")
            localVideoTrack?.setEnabled(true)

            callContext.localVideoSource = videoSource
            callContext.localVideoTrack = localVideoTrack

            localMediaStream?.addTrack(localVideoTrack)
        }
    }

    private fun attachViewRenderersInternal() {
        // render local video in pip view
        localSurfaceRenderer?.get()?.let { pipSurface ->
            pipSurface.setMirror(true)
            currentCall?.localVideoTrack?.addSink(pipSurface)
        }

        // If remote track exists, then sink it to surface
        remoteSurfaceRenderer?.get()?.let { participantSurface ->
            currentCall?.remoteVideoTrack?.let {
                it.addSink(participantSurface)
            }
        }
    }

    fun acceptIncomingCall() {
        Timber.v("## VOIP acceptIncomingCall from state ${currentCall?.mxCall?.state}")
        if (currentCall?.mxCall?.state == CallState.LOCAL_RINGING) {
            getTurnServer { turnServer ->
                internalAcceptIncomingCall(currentCall!!, turnServer)
            }
        }
    }

    fun detachRenderers() {
        Timber.v("## VOIP detachRenderers")
        // currentCall?.localMediaStream?.let { currentCall?.peerConnection?.removeStream(it) }
        localSurfaceRenderer?.get()?.let {
            currentCall?.localVideoTrack?.removeSink(it)
        }
        remoteSurfaceRenderer?.get()?.let {
            currentCall?.remoteVideoTrack?.removeSink(it)
        }
        localSurfaceRenderer = null
        remoteSurfaceRenderer = null
    }

    fun close() {
        CallService.onNoActiveCall(context)
        executor.execute {
            currentCall?.release()
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
        }
    }

    companion object {

        private const val AUDIO_TRACK_ID = "ARDAMSa0"

        private val DEFAULT_AUDIO_CONSTRAINTS = MediaConstraints().apply {
            // add all existing audio filters to avoid having echos
//            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"))
//
//            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
//
//            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
//
//            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
//
//            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
//            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
    }

    fun startOutgoingCall(context: Context, signalingRoomId: String, otherUserId: String, isVideoCall: Boolean) {
        Timber.v("## VOIP startOutgoingCall in room $signalingRoomId to $otherUserId isVideo $isVideoCall")
        val createdCall = sessionHolder.getSafeActiveSession()?.callSignalingService()?.createOutgoingCall(signalingRoomId, otherUserId, isVideoCall) ?: return
        val callContext = CallContext(createdCall)

        audioManager.startForCall(createdCall)
        currentCall = callContext

        executor.execute {
            callContext.remoteCandidateSource = ReplaySubject.create()
        }

        // start the activity now
        context.startActivity(VectorCallActivity.newIntent(context, createdCall))
    }

    override fun onCallIceCandidateReceived(mxCall: MxCall, iceCandidatesContent: CallCandidatesContent) {
        Timber.v("## VOIP onCallIceCandidateReceived for call ${mxCall.callId}")
        if (currentCall?.mxCall?.callId != mxCall.callId) return Unit.also {
            Timber.w("## VOIP ignore ice candidates from other call")
        }
        val callContext = currentCall ?: return

        executor.execute {
            iceCandidatesContent.candidates.forEach {
                Timber.v("## VOIP onCallIceCandidateReceived for call ${mxCall.callId} sdp: ${it.candidate}")
                val iceCandidate = IceCandidate(it.sdpMid, it.sdpMLineIndex, it.candidate)
                callContext.remoteCandidateSource?.onNext(iceCandidate)
            }
        }
    }

    override fun onCallInviteReceived(mxCall: MxCall, callInviteContent: CallInviteContent) {
        Timber.v("## VOIP onCallInviteReceived callId ${mxCall.callId}")
        // TODO What if a call is currently active?
        if (currentCall != null) {
            Timber.w("## VOIP TODO: Automatically reject incoming call?")
            mxCall.hangUp()
            audioManager.stop()
            return
        }

        val callContext = CallContext(mxCall)
        currentCall = callContext
        audioManager.startForCall(mxCall)
        executor.execute {
            callContext.remoteCandidateSource = ReplaySubject.create()
        }

        CallService.onIncomingCall(context,
                mxCall.isVideoCall,
                mxCall.otherUserId,
                mxCall.roomId,
                sessionHolder.getSafeActiveSession()?.myUserId ?: "",
                mxCall.callId)

        callContext.offerSdp = callInviteContent.offer
    }

    private fun createAnswer() {
        Timber.w("## VOIP createAnswer")
        val call = currentCall ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (call.mxCall.isVideoCall) "true" else "false"))
        }
        executor.execute {
            call.peerConnection?.createAnswer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(p0: SessionDescription?) {
                    if (p0 == null) return
                    call.peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, p0)
                    // Now need to send it
                    call.mxCall.accept(p0)
                }
            }, constraints)
        }
    }

    fun muteCall(muted: Boolean) {
        currentCall?.localAudioTrack?.setEnabled(!muted)
    }

    fun enableVideo(enabled: Boolean) {
        currentCall?.localVideoTrack?.setEnabled(enabled)
    }

    fun endCall() {
        currentCall?.cameraAvailabilityCallback?.let { cameraAvailabilityCallback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
            }
        }
        currentCall?.mxCall?.hangUp()
        currentCall = null
        audioManager.stop()
        close()
    }

    override fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        val call = currentCall ?: return
        if (call.mxCall.callId != callAnswerContent.callId) return Unit.also {
            Timber.w("onCallAnswerReceived for non active call? ${callAnswerContent.callId}")
        }
        executor.execute {
            Timber.v("## VOIP onCallAnswerReceived ${callAnswerContent.callId}")
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, callAnswerContent.answer.sdp)
            call.peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            }, sdp)
        }
    }

    override fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        val call = currentCall ?: return
        if (call.mxCall.callId != callHangupContent.callId) return Unit.also {
            Timber.w("onCallHangupReceived for non active call? ${callHangupContent.callId}")
        }
        call.mxCall.state = CallState.TERMINATED
        currentCall = null
        close()
    }

    private inner class StreamObserver(val callContext: CallContext) : PeerConnection.Observer {

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Timber.v("## VOIP StreamObserver onConnectionChange: $newState")
            when (newState) {
                /**
                 * Every ICE transport used by the connection is either in use (state "connected" or "completed")
                 * or is closed (state "closed"); in addition, at least one transport is either "connected" or "completed"
                 */
                PeerConnection.PeerConnectionState.CONNECTED    -> {
                    callContext.mxCall.state = CallState.CONNECTED
                }
                /**
                 * One or more of the ICE transports on the connection is in the "failed" state.
                 */
                PeerConnection.PeerConnectionState.FAILED       -> {
                    endCall()
                }
                /**
                 * At least one of the connection's ICE transports (RTCIceTransports or RTCDtlsTransports) are in the "new" state,
                 * and none of them are in one of the following states: "connecting", "checking", "failed", or "disconnected",
                 * or all of the connection's transports are in the "closed" state.
                 */
                PeerConnection.PeerConnectionState.NEW,

                    /**
                     * One or more of the ICE transports are currently in the process of establishing a connection;
                     * that is, their RTCIceConnectionState is either "checking" or "connected", and no transports are in the "failed" state
                     */
                PeerConnection.PeerConnectionState.CONNECTING   -> {
                    callContext.mxCall.state = CallState.CONNECTING
                }
                /**
                 * The RTCPeerConnection is closed.
                 * This value was in the RTCSignalingState enum (and therefore found by reading the value of the signalingState)
                 * property until the May 13, 2016 draft of the specification.
                 */
                PeerConnection.PeerConnectionState.CLOSED       -> {
                }
                /**
                 * At least one of the ICE transports for the connection is in the "disconnected" state and none of
                 * the other transports are in the state "failed", "connecting", or "checking".
                 */
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                }
                null                                            -> {
                }
            }
        }

        override fun onIceCandidate(iceCandidate: IceCandidate) {
            Timber.v("## VOIP StreamObserver onIceCandidate: $iceCandidate")
            callContext.iceCandidateSource.onNext(iceCandidate)
        }

        override fun onDataChannel(dc: DataChannel) {
            Timber.v("## VOIP StreamObserver onDataChannel: ${dc.state()}")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Timber.v("## VOIP StreamObserver onIceConnectionReceivingChange: $receiving")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Timber.v("## VOIP StreamObserver onIceConnectionChange IceConnectionState:$newState")
            when (newState) {
                /**
                 * the ICE agent is gathering addresses or is waiting to be given remote candidates through
                 * calls to RTCPeerConnection.addIceCandidate() (or both).
                 */
                PeerConnection.IceConnectionState.NEW          -> {
                }
                /**
                 * The ICE agent has been given one or more remote candidates and is checking pairs of local and remote candidates
                 * against one another to try to find a compatible match, but has not yet found a pair which will allow
                 * the peer connection to be made. It's possible that gathering of candidates is also still underway.
                 */
                PeerConnection.IceConnectionState.CHECKING     -> {
                }

                /**
                 * A usable pairing of local and remote candidates has been found for all components of the connection,
                 * and the connection has been established.
                 * It's possible that gathering is still underway, and it's also possible that the ICE agent is still checking
                 * candidates against one another looking for a better connection to use.
                 */
                PeerConnection.IceConnectionState.CONNECTED    -> {
                }
                /**
                 * Checks to ensure that components are still connected failed for at least one component of the RTCPeerConnection.
                 * This is a less stringent test than "failed" and may trigger intermittently and resolve just as spontaneously on less reliable networks,
                 * or during temporary disconnections. When the problem resolves, the connection may return to the "connected" state.
                 */
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                }
                /**
                 * The ICE candidate has checked all candidates pairs against one another and has failed to find
                 * compatible matches for all components of the connection.
                 * It is, however, possible that the ICE agent did find compatible connections for some components.
                 */
                PeerConnection.IceConnectionState.FAILED       -> {
                    callContext.mxCall.hangUp()
                }
                /**
                 *  The ICE agent has finished gathering candidates, has checked all pairs against one another, and has found a connection for all components.
                 */
                PeerConnection.IceConnectionState.COMPLETED    -> {
                }
                /**
                 * The ICE agent for this RTCPeerConnection has shut down and is no longer handling requests.
                 */
                PeerConnection.IceConnectionState.CLOSED       -> {
                }
            }
        }

        override fun onAddStream(stream: MediaStream) {
            Timber.v("## VOIP StreamObserver onAddStream: $stream")
            executor.execute {
                // reportError("Weird-looking stream: " + stream);
                if (stream.audioTracks.size > 1 || stream.videoTracks.size > 1) {
                    Timber.e("## VOIP StreamObserver weird looking stream: $stream")
                    // TODO maybe do something more??
                    callContext.mxCall.hangUp()
                    return@execute
                }

                if (stream.videoTracks.size == 1) {
                    val remoteVideoTrack = stream.videoTracks.first()
                    remoteVideoTrack.setEnabled(true)
                    callContext.remoteVideoTrack = remoteVideoTrack
                    // sink to renderer if attached
                    remoteSurfaceRenderer?.get().let { remoteVideoTrack.addSink(it) }
                }
            }
        }

        override fun onRemoveStream(stream: MediaStream) {
            Timber.v("## VOIP StreamObserver onRemoveStream")
            executor.execute {
                remoteSurfaceRenderer?.get()?.let {
                    callContext.remoteVideoTrack?.removeSink(it)
                }
                callContext.remoteVideoTrack = null
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Timber.v("## VOIP StreamObserver onIceGatheringChange: $newState")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Timber.v("## VOIP StreamObserver onSignalingChange: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
            Timber.v("## VOIP StreamObserver onIceCandidatesRemoved: ${candidates.contentToString()}")
        }

        override fun onRenegotiationNeeded() {
            Timber.v("## VOIP StreamObserver onRenegotiationNeeded")
            // Should not do anything, for now we follow a pre-agreed-upon
            // signaling/negotiation protocol.
        }

        /**
         * This happens when a new track of any kind is added to the media stream.
         * This event is fired when the browser adds a track to the stream
         * (such as when a RTCPeerConnection is renegotiated or a stream being captured using HTMLMediaElement.captureStream()
         * gets a new set of tracks because the media element being captured loaded a new source.
         */
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
            Timber.v("## VOIP StreamObserver onAddTrack")
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    inner class CameraRestarter(val cameraId: String, val callId: String) : CameraManager.AvailabilityCallback() {

        override fun onCameraAvailable(cameraId: String) {
            if (this.cameraId == cameraId && currentCall?.mxCall?.callId == callId) {
                // re-start the capture
                // TODO notify that video is enabled
                videoCapturer?.startCapture(1280, 720, 30)
                (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)
                        ?.unregisterAvailabilityCallback(this)
            }
        }
    }
}
