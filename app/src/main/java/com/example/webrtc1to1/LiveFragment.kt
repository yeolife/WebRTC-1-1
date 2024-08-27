package com.example.webrtc1to1

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.pm.ActivityInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.webrtc1to1.databinding.FragmentLiveBinding
import com.example.webrtc1to1.webRtc.utils.PermissionHelper
import com.example.webrtc1to1.webRtc.SignalingClient
import com.example.webrtc1to1.webRtc.WebRTCSessionState
import com.example.webrtc1to1.webRtc.peer.StreamPeerConnectionFactory
import com.example.webrtc1to1.webRtc.sessions.WebRtcSessionManager
import com.example.webrtc1to1.webRtc.sessions.WebRtcSessionManagerImpl
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class LiveFragment : Fragment() {
    private var user: User = User(0, false)

    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LiveViewModel by viewModels()
    
    private lateinit var permissionHelper: PermissionHelper

    // 상태
    private var prevState: WebRTCSessionState = WebRTCSessionState.Offline
    private var isMirrorMode = true

    // 기능
    private lateinit var sessionManager: WebRtcSessionManager

    // 화면
    private lateinit var localRenderer: VideoTextureViewRenderer
    private lateinit var remoteRenderer: VideoTextureViewRenderer
    private lateinit var draggableContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = WebRtcSessionManagerImpl(
            requireContext(),
            SignalingClient(),
            StreamPeerConnectionFactory(requireContext())
        )
        
        arguments?.let {
            user = it.getSerializable(ARG_PARAM) as User
            sessionManager.signalingClient.updateInfo(user.liveId, if(user.isTeacher) 1 else 0)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initPermission()

        initListener()

        setFullscreen()

        initInMoveLocalView()

        setSpeakerphoneOn(true)

        renderInit()

        observeSessionState()

        observeCallMediaState()
    }

    private fun isPermission() {
        sessionManager.onLocalScreen()
    }

    private fun initPermission() {
        permissionHelper =
            PermissionHelper(this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                ::popBack,
                ::isPermission).apply {
                launchPermission()
            }
    }

    private fun initListener() {
        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    popBack()
                }
            })

        with(binding) {
            ibtnMic.setOnClickListener {
                viewModel.toggleMicrophoneState(viewModel.callMediaState.value.isMicrophoneEnabled.not())
            }

            ibtnVideo.setOnClickListener {
                viewModel.toggleCameraState(viewModel.callMediaState.value.isCameraEnabled.not())
            }

            ibtnCamSwitch.setOnClickListener {
                if (sessionManager.signalingClient.sessionStateFlow.value == WebRTCSessionState.Active) {
                    isMirrorMode = !isMirrorMode
                    localVideoCallScreen.setMirror(isMirrorMode)

                    sessionManager.flipCamera()
                }
            }

            ibtnCancel.setOnClickListener { popBack() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initInMoveLocalView() {
        binding.localVideoCallScreen.setMirror(isMirrorMode)

        draggableContainer = binding.draggableContainer
        draggableContainer.setOnTouchListener { view, event ->
            val parent = view.parent as View
            val parentWidth = parent.width
            val parentHeight = parent.height

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    viewModel.updateOffset(view.x - event.rawX, view.y - event.rawY)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val newX = event.rawX + viewModel.offsetX.value
                    val newY = event.rawY + viewModel.offsetY.value

                    val clampedX = newX.coerceIn(0f, parentWidth - view.width.toFloat())
                    val clampedY = newY.coerceIn(0f, parentHeight - view.height.toFloat())

                    view.animate()
                        .x(clampedX)
                        .y(clampedY)
                        .setDuration(0)
                        .start()
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOutMoveLocalView() {
        draggableContainer = binding.draggableContainer
        draggableContainer.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    viewModel.updateOffset(view.x - event.rawX, view.y - event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + viewModel.offsetX.value)
                        .y(event.rawY + viewModel.offsetY.value)
                        .setDuration(0)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    private fun observeSessionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionManager.signalingClient.sessionStateFlow.collect { state ->
                    handleSessionState(state)
                }
            }
        }
    }

    private fun handleSessionState(state: WebRTCSessionState) {
        if(state == WebRTCSessionState.Ready || state == WebRTCSessionState.Creating)
            onBuffering(true)
        else if(state == WebRTCSessionState.Offline || state == WebRTCSessionState.Impossible || state == WebRTCSessionState.Active)
            onBuffering(false)

        when (state) {
            WebRTCSessionState.Offline -> {
                if (prevState != WebRTCSessionState.Offline) {
                    runCatching  { sessionManager.disconnect() }
                }
            }

            WebRTCSessionState.Impossible -> {
                if(prevState == WebRTCSessionState.Active)
                    renderInitWhenImpossible()
            }

            WebRTCSessionState.Ready -> {
                if (user.isTeacher)
                    sessionManager.onSessionReady()
            }

            WebRTCSessionState.Creating -> {
                if (!user.isTeacher)
                    sessionManager.onSessionReady()
            }

            WebRTCSessionState.Active -> { setBackLocalScreenSize() }
        }

        prevState = state
    }

    private fun renderInitWhenImpossible() {
        val layoutParams = binding.draggableContainer.layoutParams

        val startWidth = layoutParams.width
        val startHeight = layoutParams.height

        val parentWidth = (binding.draggableContainer.parent as View).width
        val parentHeight = (binding.draggableContainer.parent as View).height

        val widthAnimator = ValueAnimator.ofInt(startWidth, parentWidth).apply {
            addUpdateListener { animator ->
                layoutParams.width = animator.animatedValue as Int
                binding.draggableContainer.layoutParams = layoutParams
            }
        }

        val heightAnimator = ValueAnimator.ofInt(startHeight, parentHeight).apply {
            addUpdateListener { animator ->
                layoutParams.height = animator.animatedValue as Int
                binding.draggableContainer.layoutParams = layoutParams
            }
        }

        val animatorSet = AnimatorSet().apply {
            playTogether(widthAnimator, heightAnimator)
        }

        animatorSet.start()
    }

    private fun setBackLocalScreenSize() {
        val layoutParams = binding.draggableContainer.layoutParams

        val startWidth = binding.root.width
        val startHeight = binding.root.height
        val endWidth = 525
        val endHeight = 394

        val widthAnimator = ValueAnimator.ofInt(startWidth, endWidth).apply {
            addUpdateListener { animator ->
                layoutParams.width = animator.animatedValue as Int
                binding.draggableContainer.layoutParams = layoutParams
            }
        }

        val heightAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            addUpdateListener { animator ->
                layoutParams.height = animator.animatedValue as Int
                binding.draggableContainer.layoutParams = layoutParams
            }
        }

        val animatorSet = AnimatorSet().apply {
            duration = 300
            playTogether(widthAnimator, heightAnimator)
        }

        animatorSet.start()
    }

    private fun onBuffering(isEnabled: Boolean) {
        if(isEnabled) {
            binding.lav.playAnimation()
            binding.lav.isVisible = true
        } else {
            binding.lav.pauseAnimation()
            binding.lav.isVisible = false
        }
    }

    private fun observeCallMediaState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.callMediaState.collectLatest { state ->
                    handleMicrophoneState(state.isMicrophoneEnabled)
                    handleCameraState(state.isCameraEnabled)
                }
            }
        }
    }

    private fun handleMicrophoneState(isEnabled: Boolean) {
        runCatching {
            if (sessionManager.signalingClient.sessionStateFlow.value == WebRTCSessionState.Active)
                sessionManager.enableMicrophone(isEnabled)
        }.onSuccess {
            binding.ibtnMic.setImageResource(if (isEnabled) R.drawable.baseline_mic_24 else R.drawable.baseline_mic_off_24)
        }
    }

    private fun handleCameraState(isEnabled: Boolean) {
        runCatching {
            if (sessionManager.signalingClient.sessionStateFlow.value == WebRTCSessionState.Active)
                sessionManager.enableCamera(isEnabled)
        }.onSuccess {
            binding.ibtnVideo.setImageResource(if (isEnabled) R.drawable.baseline_videocam_24 else R.drawable.baseline_videocam_off_24)
        }
    }

    private fun renderInit() {
        localRenderer = binding.localVideoCallScreen
        remoteRenderer = binding.remoteVideoCallScreen

        localRenderer.init(sessionManager.peerConnectionFactory.eglBaseContext,
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit

                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
            })

        remoteRenderer.init(sessionManager.peerConnectionFactory.eglBaseContext,
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit

                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
            })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectVideoTrack(sessionManager.localVideoTrackFlow, localRenderer)
                collectVideoTrack(sessionManager.remoteVideoTrackFlow, remoteRenderer)
            }
        }
    }

    private fun CoroutineScope.collectVideoTrack(flow: Flow<VideoTrack?>, renderer: VideoTextureViewRenderer) = launch {
        flow.collectLatest { videoTrack ->
            runCatching {
                setupVideoTrack(videoTrack, renderer)
            }
        }
    }

    private fun cleanVideoTrack(videoTrack: VideoTrack?, renderer: VideoTextureViewRenderer) {
        videoTrack?.removeSink(renderer)
    }

    private fun setupVideoTrack(videoTrack: VideoTrack?, renderer: VideoTextureViewRenderer) {
        cleanVideoTrack(videoTrack, renderer)

        videoTrack?.addSink(renderer)
    }

    private fun setFullscreen() = with(requireActivity() as MainActivity) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        supportActionBar?.hide()
    }

    private fun exitFullscreen() = with(requireActivity() as MainActivity) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        supportActionBar?.show()
    }

    private fun setSpeakerphoneOn(on: Boolean) {
        val audioManager =  requireActivity().getSystemService(AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

            if (on && speakerDevice != null) {
                audioManager.setCommunicationDevice(speakerDevice)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    private fun popBack() {
        runCatching {
            sessionManager.disconnect()
        }.onSuccess {
            setSpeakerphoneOn(false)

            exitFullscreen()

            parentFragmentManager.popBackStack()
        }
    }

    companion object {
        private const val ARG_PARAM = "user"
        
        fun newInstance(user: User) =
            LiveFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_PARAM, user)
                }
            }
    }
}