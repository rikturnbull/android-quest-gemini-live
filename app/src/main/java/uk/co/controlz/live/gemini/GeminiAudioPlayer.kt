package uk.co.controlz.live.gemini

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

import java.util.concurrent.ConcurrentLinkedQueue

import kotlinx.coroutines.*

class GeminiAudioPlayer {
    companion object {
        private const val TAG = "GeminiAudioPlayer"

        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    init {
        initializeAudioTrack()
    }

    private fun initializeAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            )

            val bufferSize = maxOf(minBufferSize, 4096)

            val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

            val audioFormat =
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT).build()

            audioTrack =
                AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    fun playAudio(audioData: ByteArray) {
        if (audioData.isEmpty()) {
            Log.w(TAG, "Received empty audio data")
            return
        }

        audioQueue.offer(audioData)

        if (!isPlaying) {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (isPlaying || audioTrack == null) {
            return
        }

        isPlaying = true

        scope.launch {
            try {
                audioTrack?.play()
                Log.d(TAG, "Started audio playback")

                while (isPlaying && !audioQueue.isEmpty()) {
                    val audioData = audioQueue.poll()
                    if (audioData != null) {
                        playAudioChunk(audioData)
                    } else {
                        delay(10)
                    }
                }

                audioTrack?.stop()
                isPlaying = false
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio playback: ${e.message}", e)
                isPlaying = false
                audioTrack?.stop()
            }
        }
    }

    private suspend fun playAudioChunk(audioData: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val track = audioTrack ?: return@withContext

                var offset = 0
                val totalBytes = audioData.size

                while (offset < totalBytes && isPlaying) {
                    val bytesWritten = track.write(
                        audioData, offset, minOf(4096, totalBytes - offset)
                    )

                    if (bytesWritten > 0) {
                        offset += bytesWritten
                    } else if (bytesWritten == AudioTrack.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "AudioTrack invalid operation")
                        break
                    } else if (bytesWritten == AudioTrack.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioTrack bad value")
                        break
                    }

                    // Small delay to prevent busy waiting
                    if (offset < totalBytes) {
                        delay(1)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio chunk: ${e.message}", e)
            }
        }
    }

    fun stop() {
        isPlaying = false
        audioQueue.clear()

        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}", e)
        }
    }

    fun isPlaying(): Boolean = isPlaying

    fun dispose() {
        stop()
        scope.cancel()

        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing audio track: ${e.message}", e)
        }
    }
}