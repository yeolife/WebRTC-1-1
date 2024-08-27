package com.example.webrtc1to1.webRtc.audio

import com.example.webrtc1to1.webRtc.audio.AudioDevice

typealias AudioDeviceChangeListener = (
    audioDevices: List<AudioDevice>,
    selectedAudioDevice: AudioDevice?
) -> Unit
