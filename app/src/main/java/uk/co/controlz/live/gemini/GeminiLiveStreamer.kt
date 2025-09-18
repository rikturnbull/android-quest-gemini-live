package uk.co.controlz.live.gemini

import android.content.Context
import android.util.Base64
import android.util.Log

import com.google.gson.Gson

import java.util.concurrent.TimeUnit

import kotlinx.coroutines.*

import okhttp3.*
import okio.ByteString
import uk.co.controlz.live.gemini.data.*
import uk.co.controlz.live.R

class GeminiLiveStreamer(
    private val apiKey: String,
    private val context: Context
) {
    companion object {
        private const val TAG = "GeminiLiveStreamer"
    }

    private val videoSendInterval = 333L // 100: (10 FPS) 200L (5 FPS) or 333L (3 FPS)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecorder: GeminiAudioRecorder? = null
    private var webSocket: WebSocket? = null
    private var isSessionActive = false
    private var lastVideoSend = 0L

    var onGeminiResponse: ((String) -> Unit)? = null
    var onGeminiAudioResponse: ((ByteArray) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onSetupCompleted: (() -> Unit)? = null

    suspend fun connect(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isEmpty() || apiKey == "\"\"" || apiKey == "null") {
                    Log.e(TAG, "Invalid API key: empty, null, or default value")
                    return@withContext false
                }

                val geminiUrl = context.getString(R.string.gemini_url)
                val url = "$geminiUrl?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .build()

                val webSocketListener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "WebSocket connection opened")
                        this@GeminiLiveStreamer.webSocket = webSocket
                        isSessionActive = true
                        onConnectionStatusChanged?.invoke(true)

                        scope.launch {
                            sendSetupMessage()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        try {
                            val textContent = bytes.utf8()
                            handleMessage(textContent)
                        } catch (e: Exception) {
                            Log.w(TAG, "Binary message is not text: ${e.message}")
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "WebSocket closing: $code $reason")
                        isSessionActive = false
                        onConnectionStatusChanged?.invoke(false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "WebSocket closed: $code $reason")
                        isSessionActive = false
                        onConnectionStatusChanged?.invoke(false)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        Log.e(TAG, "WebSocket error: ${t.message}", t)
                        response?.let { resp ->
                            Log.e(TAG, "WebSocket error response: ${resp.code} ${resp.message}")
                            resp.body?.let { body ->
                                try {
                                    Log.e(TAG, "WebSocket error body: ${body.string()}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Could not read error response body: ${e.message}")
                                }
                            }
                        }
                        isSessionActive = false
                        onConnectionStatusChanged?.invoke(false)
                    }
                }

                webSocket = client.newWebSocket(request, webSocketListener)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Gemini: ${e.message}", e)
                false
            }
        }
    }

    private suspend fun sendSetupMessage() {
        val model = context.getString(R.string.gemini_model)
        val prompt = context.getString(R.string.prompt)
        withContext(Dispatchers.IO) {
            try {
                val setupMessage = BidiGenerateContentClientMessage(
                    setup = BidiGenerateContentSetup(
                        model = model,
                        generationConfig = GenerationConfig(
                            candidateCount = 1,
                            maxOutputTokens = 1024,
                            temperature = 0.7,
                            topP = 0.9,
                            responseModalities = listOf("AUDIO"),
                            speechConfig = SpeechConfig(
                                voiceConfig = VoiceConfig(
                                    prebuiltVoiceConfig = PrebuiltVoiceConfig(
                                        voiceName = "Aoede"
                                    )
                                )
                            )
                        ),
                        systemInstruction = SystemContent(
                            parts = listOf(
                                Part(text = prompt)
                            )
                        )
                    )
                )

                val messageJson = gson.toJson(setupMessage)
                webSocket?.send(messageJson)
                Log.i(TAG, "Gemini setup message sent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send setup message: ${e.message}", e)
            }
        }
    }

    suspend fun sendVideoFrame(frameData: ByteArray) {
        if (!isSessionActive || webSocket == null) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVideoSend < videoSendInterval) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val videoB64 = Base64.encodeToString(frameData, Base64.NO_WRAP)

                val message = BidiGenerateContentClientMessageVideo(
                    realtimeInput = BidiGenerateContentRealtimeInputVideo(
                        video = VideoData(
                            mimeType = "image/jpeg",
                            data = videoB64
                        )
                    )
                )

                val messageJson = gson.toJson(message)
                val sent = webSocket?.send(messageJson) ?: false

                if (sent) {
                    lastVideoSend = currentTime
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send video frame to Gemini: ${e.message}", e)
            }
        }
    }

    private fun handleMessage(message: String) {
        try {
            val jsonMessage = gson.fromJson(message, BidiGenerateContentServerMessage::class.java)

            if (jsonMessage.setupComplete != null) {
                Log.i(TAG, "Gemini setup completed successfully")
                val audioStarted = startAudioRecording()
                Log.i(
                    TAG,
                    "Audio recording ${if (audioStarted) "started successfully" else "failed to start"}"
                )

                onSetupCompleted?.invoke()

                return
            }

            jsonMessage.serverContent?.let { serverContent ->
                serverContent.modelTurn?.let { modelTurn ->
                    modelTurn.parts.forEach { part ->
                        part.text?.let { responseText ->
                            onGeminiResponse?.invoke(responseText)
                        }
                        
                        part.inlineData?.let { inlineData ->
                            if (inlineData.mimeType.contains("audio")) {
                                try {
                                    val audioBytes = Base64.decode(inlineData.data, Base64.DEFAULT)
                                    onGeminiAudioResponse?.invoke(audioBytes)
                                } catch (e: Exception) {
                                    Log.e(
                                        TAG,
                                        "Failed to decode audio response: ${e.message}"
                                    )
                                }
                            }
                        }
                    }
                }

                serverContent.inputTranscription?.let { transcription ->
                    Log.d(TAG, "Gemini transcription: ${transcription.text}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from Gemini: ${e.message}", e)
        }
    }

    fun startAudioRecording(): Boolean {
        if (audioRecorder?.isRecording() == true) {
            Log.w(TAG, "Audio recording already active")
            return true
        }

        if (!isSessionActive) {
            Log.e(TAG, "Cannot start audio recording - Gemini session not active")
            return false
        }

        audioRecorder = GeminiAudioRecorder(context) { audioBase64 ->
            sendAudioToGemini(audioBase64)
        }

        return audioRecorder?.startRecording() ?: false
    }

    fun stopAudioRecording() {
        audioRecorder?.stopRecording()
        audioRecorder = null
        Log.i(TAG, "Audio recording stopped")
    }

    private fun sendAudioToGemini(audioBase64: String) {
        if (!isSessionActive || webSocket == null) return

        try {
            val audioMessage = BidiGenerateContentClientMessageAudio(
                realtimeInput = BidiGenerateContentRealtimeInputAudio(
                    audio = AudioData(
                        mimeType = "audio/pcm;rate=16000",
                        data = audioBase64
                    )
                )
            )

            val messageJson = gson.toJson(audioMessage)
            webSocket?.send(messageJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio to Gemini: ${e.message}", e)
        }
    }

    fun isActive(): Boolean = isSessionActive && webSocket != null

    fun close() {
        Log.i(TAG, "Closing Gemini connection...")
        stopAudioRecording() // Stop audio recording when closing
        isSessionActive = false
        webSocket?.close(1000, "Client closing")
        webSocket = null
        audioRecorder?.release()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}