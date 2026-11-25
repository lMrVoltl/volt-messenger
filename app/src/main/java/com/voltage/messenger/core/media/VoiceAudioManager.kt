package com.voltage.messenger.core.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f
)

class VoiceAudioManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var currentRecordFile: File? = null
    private var recordStartMs: Long = 0L

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playback = MutableStateFlow(VoicePlaybackState())
    val playback: StateFlow<VoicePlaybackState> = _playback.asStateFlow()

    fun startRecording(): Boolean {
        stopRecording(discard = true)
        return try {
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val outFile = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(outFile.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentRecordFile = outFile
            recordStartMs = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            stopRecording(discard = true)
            false
        }
    }

    fun stopRecording(discard: Boolean = false): Pair<File, Int>? {
        val r = recorder
        val file = currentRecordFile
        val elapsedSec = ((System.currentTimeMillis() - recordStartMs) / 1000L).toInt().coerceAtLeast(1)
        recorder = null
        currentRecordFile = null

        if (r != null) {
            try {
                r.stop()
            } catch (_: Exception) {
            }
            try {
                r.release()
            } catch (_: Exception) {
            }
        }

        if (discard || file == null || !file.exists() || file.length() < 100) {
            file?.delete()
            return null
        }
        return file to elapsedSec
    }

    fun togglePlay(messageId: String, filePath: String) {
        val current = _playback.value
        if (current.activeMessageId == messageId && player != null) {
            val p = player ?: return
            if (p.isPlaying) {
                p.pause()
                _playback.value = current.copy(isPlaying = false)
            } else {
                p.start()
                _playback.value = current.copy(isPlaying = true)
                startProgressLoop(messageId)
            }
            return
        }

        stopPlayback()
        val file = File(filePath)
        if (!file.exists()) return

        try {
            val mp = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    _playback.value = VoicePlaybackState(
                        activeMessageId = messageId,
                        isPlaying = false,
                        progress = 0f
                    )
                    progressJob?.cancel()
                }
                start()
            }
            player = mp
            _playback.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                progress = 0f
            )
            startProgressLoop(messageId)
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    fun seekTo(messageId: String, fraction: Float) {
        val p = player ?: return
        if (_playback.value.activeMessageId != messageId) return
        val dur = p.duration
        if (dur > 0) {
            val target = (dur * fraction.coerceIn(0f, 1f)).toInt()
            p.seekTo(target)
            _playback.value = _playback.value.copy(progress = fraction.coerceIn(0f, 1f))
        }
    }

    fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        _playback.value = VoicePlaybackState()
    }

    private fun startProgressLoop(messageId: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val p = player ?: break
                if (!p.isPlaying) break
                val dur = p.duration.coerceAtLeast(1)
                val pos = p.currentPosition.coerceIn(0, dur)
                _playback.value = VoicePlaybackState(
                    activeMessageId = messageId,
                    isPlaying = true,
                    progress = pos.toFloat() / dur.toFloat()
                )
                delay(32L)
            }
        }
    }

    fun destroy() {
        stopRecording(discard = true)
        stopPlayback()
        scope.cancel()
    }
}
