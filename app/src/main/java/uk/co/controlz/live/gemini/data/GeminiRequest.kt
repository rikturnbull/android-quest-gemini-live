package uk.co.controlz.live.gemini.data

/**
 * Data classes for Gemini API requests based on official Live API documentation
 * Using official API naming conventions
 */

/**
 * PrebuiltVoiceConfig - configuration for prebuilt voice
 */
data class PrebuiltVoiceConfig(
    val voiceName: String
)

/**
 * VoiceConfig - voice configuration settings
 */
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

/**
 * SpeechConfig - speech configuration settings
 */
data class SpeechConfig(
    val voiceConfig: VoiceConfig
)

/**
 * GenerationConfig - generation configuration for the Gemini model
 * Based on the official API documentation
 */
data class GenerationConfig(
    val candidateCount: Int,
    val maxOutputTokens: Int,
    val temperature: Double,
    val topP: Double,
    val responseModalities: List<String>,
    val speechConfig: SpeechConfig
)

/**
 * Content - represents content with parts (used for system instruction)
 * Based on the official "Content" type from API documentation
 */
data class SystemContent(
    val parts: List<Part>
)

/**
 * BidiGenerateContentSetup - session configuration to be sent in the first message
 */
data class BidiGenerateContentSetup(
    val model: String,
    val generationConfig: GenerationConfig,
    val systemInstruction: SystemContent
)

/**
 * BidiGenerateContentClientMessage with setup - initial setup message for Gemini session
 */
data class BidiGenerateContentClientMessage(
    val setup: BidiGenerateContentSetup
)

/**
 * BidiGenerateContentRealtimeInput for video streaming
 */
data class BidiGenerateContentRealtimeInputVideo(
    val video: VideoData
)

/**
 * Client message for streaming video frames
 */
data class BidiGenerateContentClientMessageVideo(
    val realtimeInput: BidiGenerateContentRealtimeInputVideo
)

/**
 * BidiGenerateContentRealtimeInput for audio streaming
 */
data class BidiGenerateContentRealtimeInputAudio(
    val audio: AudioData
)

/**
 * Client message for streaming audio data
 */
data class BidiGenerateContentClientMessageAudio(
    val realtimeInput: BidiGenerateContentRealtimeInputAudio
)