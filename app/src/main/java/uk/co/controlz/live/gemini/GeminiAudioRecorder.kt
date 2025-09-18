package uk.co.controlz.live.gemini

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat

import kotlinx.coroutines.*

class GeminiAudioRecorder(
    private val context: Context,
    private val onAudioData: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiAudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return true
        }

        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Audio recording permission not granted")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                recordAudio()
            }

            Log.i(TAG, "Audio recording started")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        recordingJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording: ${e.message}", e)
        }
    }

    private suspend fun recordAudio() {
        val buffer = ByteArray(bufferSize)

        while (isRecording && audioRecord != null) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (bytesRead > 0) {
                    val audioChunk = buffer.copyOf(bytesRead)
                    val base64Audio = Base64.encodeToString(audioChunk, Base64.NO_WRAP)

                    withContext(Dispatchers.Main) {
                        onAudioData(base64Audio)
                    }
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Error reading audio data: $bytesRead")
                    break
                }

                delay(100)
            } catch (e: Exception) {
                Log.e(TAG, "Error recording audio: ${e.message}", e)
                if (e is CancellationException) break
            }
        }
    }

    fun isRecording(): Boolean = isRecording

    fun release() {
        stopRecording()
        scope.cancel()
    }
}