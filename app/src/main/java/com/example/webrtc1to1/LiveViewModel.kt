package com.example.webrtc1to1

import android.app.Application
import android.content.Context
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import com.example.webrtc1to1.webRtc.SignalingClient
import com.example.webrtc1to1.webRtc.peer.StreamPeerConnectionFactory
import com.example.webrtc1to1.webRtc.sessions.WebRtcSessionManager
import com.example.webrtc1to1.webRtc.sessions.WebRtcSessionManagerImpl
import com.example.webrtc1to1.webRtc.utils.CallMediaState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LiveViewModel(
    application: Application
): AndroidViewModel(application) {
    val sessionManager: WebRtcSessionManager = WebRtcSessionManagerImpl(
        getApplication(),
        SignalingClient(),
        StreamPeerConnectionFactory(getApplication())
    )

    private val _callMediaState = MutableStateFlow(CallMediaState())
    val callMediaState: StateFlow<CallMediaState> = _callMediaState

    private val _offsetX = MutableStateFlow(0f)
    val offsetX: StateFlow<Float> get() = _offsetX

    private val _offsetY = MutableStateFlow(0f)
    val offsetY: StateFlow<Float> get() = _offsetY

    fun updateOffset(x: Float, y: Float) {
        _offsetX.value = x
        _offsetY.value = y
    }

    fun toggleMicrophoneState(isEnabled: Boolean) {
        _callMediaState.value = _callMediaState.value.copy(isMicrophoneEnabled = isEnabled)
    }

    fun toggleCameraState(isEnabled: Boolean) {
        _callMediaState.value = _callMediaState.value.copy(isCameraEnabled = isEnabled)
    }
}
