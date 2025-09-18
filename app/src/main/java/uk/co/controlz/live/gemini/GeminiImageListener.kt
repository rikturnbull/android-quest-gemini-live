package uk.co.controlz.live.gemini

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log

import java.io.ByteArrayOutputStream

import kotlinx.coroutines.*

import uk.co.controlz.live.camera.CameraController

class GeminiImageListener(
    private val geminiStreamer: GeminiLiveStreamer
) : CameraController.ImageAvailableListener {

    companion object {
        private const val TAG = "GeminiImageListener"
        private const val JPEG_QUALITY = 85
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var frameCount = 0
    private var lastFrameTime = 0L
    
    override fun onNewImage(image: Image, width: Int, height: Int, finally: () -> Unit) {
        frameCount++
        val currentTime = System.currentTimeMillis()

        val frameInterval = if (lastFrameTime > 0) currentTime - lastFrameTime else 0
        lastFrameTime = currentTime
        
//        if (frameCount % 30 == 0) {
//            val fps = if (frameInterval > 0) 1000f / frameInterval else 0f
//            val geminiStatus = if (geminiStreamer.isActive()) "Active" else "Inactive"
//            Log.i(TAG, "Frame $frameCount | FPS: ${"%.1f".format(fps)} | Gemini: $geminiStatus")
//        }
        
        scope.launch {
            try {
                processImageFrame(image, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image frame: ${e.message}", e)
            } finally {
                finally()
            }
        }
    }

    private suspend fun processImageFrame(image: Image, width: Int, height: Int) {
//        if (!geminiStreamer.isActive()) {
//            Log.d(TAG, "Gemini not active, skipping frame")
//            return
//        }
        
        try {
            val jpegData = convertImageToJpeg(image, width, height)
            if (jpegData != null) {
                geminiStreamer.sendVideoFrame(jpegData)
            } else {
                Log.w(TAG, "Failed to convert image to JPEG")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processImageFrame: ${e.message}", e)
        }
    }

    private fun convertImageToJpeg(image: Image, width: Int, height: Int): ByteArray? {
        return try {
            when (image.format) {
                ImageFormat.YUV_420_888 -> {
                    convertYuv420ToJpeg(image, width, height)
                }
                ImageFormat.JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes
                }
                else -> {
                    Log.w(TAG, "Unsupported image format: ${image.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to JPEG: ${e.message}", e)
            null
        }
    }

    private fun convertYuv420ToJpeg(image: Image, width: Int, height: Int): ByteArray? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer
            
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            val nv21 = ByteArray(ySize + uSize + vSize)
            
            yBuffer.get(nv21, 0, ySize)
            
            val uvBuffer = ByteArray(uSize + vSize)
            vBuffer.get(uvBuffer, 0, vSize)
            uBuffer.get(uvBuffer, vSize, uSize)
            
            var uvIndex = 0
            for (i in ySize until nv21.size step 2) {
                if (uvIndex < uvBuffer.size - 1) {
                    nv21[i] = uvBuffer[uvIndex + 1] // V
                    nv21[i + 1] = uvBuffer[uvIndex] // U
                    uvIndex += 2
                }
            }
            
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            val rect = Rect(0, 0, width, height)
            
            val success = yuvImage.compressToJpeg(rect, JPEG_QUALITY, outputStream)
            if (success) {
                outputStream.toByteArray()
            } else {
                Log.w(TAG, "Failed to compress YUV to JPEG")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV420 to JPEG: ${e.message}", e)
            null
        }
    }

    fun dispose() {
        scope.cancel()
    }
}