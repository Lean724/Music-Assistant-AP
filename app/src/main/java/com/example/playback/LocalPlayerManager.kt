package com.example.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocalPlayerManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionSec = MutableStateFlow(0)
    val currentPositionSec: StateFlow<Int> = _currentPositionSec

    private val _durationSec = MutableStateFlow(0)
    val durationSec: StateFlow<Int> = _durationSec

    private var progressJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Simulated playback in case streaming is blocked or fails
    private var isSimulated = false
    private var simulatedTimerJob: Job? = null

    // App-level playback volume (0.0-1.0), independent of the phone's system media volume.
    // Applied to the MediaPlayer directly via setVolume() so our in-app slider actually controls
    // loudness of the track itself, instead of only the OS hardware volume rocker doing anything.
    private var currentVolume: Float = 1.0f

    /** Sets local playback volume. [vol] is 0-100 (matches the UI slider's range). */
    fun setVolume(vol: Int) {
        currentVolume = (vol / 100f).coerceIn(0f, 1f)
        mediaPlayer?.setVolume(currentVolume, currentVolume)
    }

    fun play(streamUrl: String?, fallbackDurationSec: Int = 180, token: String? = null) {
        stop()
        isSimulated = false

        if (streamUrl.isNullOrEmpty()) {
            startSimulation(fallbackDurationSec)
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                if (!token.isNullOrEmpty() && streamUrl.startsWith("http")) {
                    val headers = mapOf("Authorization" to "Bearer $token")
                    setDataSource(context, android.net.Uri.parse(streamUrl), headers)
                } else {
                    setDataSource(streamUrl)
                }
                setOnPreparedListener { mp ->
                    mp.setVolume(currentVolume, currentVolume)
                    mp.start()
                    _isPlaying.value = true
                    _durationSec.value = mp.duration / 1000
                    startProgressTracker()
                }
                setOnCompletionListener {
                    stop()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("LocalPlayerManager", "MediaPlayer error: what=$what, extra=$extra. Falling back to simulated playback.")
                    startSimulation(fallbackDurationSec)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("LocalPlayerManager", "Failed to init MediaPlayer: ${e.message}. Falling back to simulation.")
            startSimulation(fallbackDurationSec)
        }
    }

    fun pause() {
        if (isSimulated) {
            _isPlaying.value = false
            simulatedTimerJob?.cancel()
            return
        }

        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                progressJob?.cancel()
            }
        }
    }

    fun resume(fallbackDurationSec: Int = 180) {
        if (isSimulated) {
            _isPlaying.value = true
            resumeSimulation(fallbackDurationSec)
            return
        }

        mediaPlayer?.let {
            try {
                it.start()
                _isPlaying.value = true
                startProgressTracker()
            } catch (e: Exception) {
                startSimulation(fallbackDurationSec)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        simulatedTimerJob?.cancel()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e("LocalPlayerManager", "Error stopping player: ${e.message}")
            }
        }
        mediaPlayer = null
        _isPlaying.value = false
        _currentPositionSec.value = 0
        _durationSec.value = 0
    }

    fun seekTo(seconds: Int) {
        _currentPositionSec.value = seconds
        if (isSimulated) {
            return
        }

        mediaPlayer?.let {
            try {
                it.seekTo(seconds * 1000)
            } catch (e: Exception) {
                Log.e("LocalPlayerManager", "Error seeking: ${e.message}")
            }
        }
    }

    fun release() {
        stop()
        coroutineScope.cancel()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch {
            while (isActive) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _currentPositionSec.value = it.currentPosition / 1000
                    }
                }
                delay(1000)
            }
        }
    }

    private fun startSimulation(durationSeconds: Int) {
        isSimulated = true
        _isPlaying.value = true
        _durationSec.value = durationSeconds
        _currentPositionSec.value = 0
        resumeSimulation(durationSeconds)
    }

    private fun resumeSimulation(durationSeconds: Int) {
        simulatedTimerJob?.cancel()
        simulatedTimerJob = coroutineScope.launch {
            while (isActive && _isPlaying.value) {
                if (_currentPositionSec.value >= durationSeconds) {
                    _isPlaying.value = false
                    _currentPositionSec.value = 0
                    break
                }
                delay(1000)
                _currentPositionSec.value += 1
            }
        }
    }
}
