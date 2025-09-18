package uk.co.controlz.live.gemini.data

/**
 * Common data classes used across Gemini requests and responses
 * Based on official Live API documentation naming conventions
 */

/**
 * Part - represents a text part in content
 * Based on the official "Part" type from API documentation
 */
data class Part(
    val text: String
)

/**
 * InlineData - represents inline data such as audio or video content
 * Based on the official API documentation
 */
data class InlineData(
    val mimeType: String,
    val data: String
)

/**
 * Blob - represents binary data for video streaming
 * Based on the official "Blob" type from API documentation
 */
data class VideoData(
    val mimeType: String,
    val data: String
)

/**
 * Blob - represents binary data for audio streaming
 * Based on the official "Blob" type from API documentation
 */
data class AudioData(
    val mimeType: String,
    val data: String
)