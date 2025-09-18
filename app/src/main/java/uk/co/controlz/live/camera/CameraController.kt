package uk.co.controlz.live.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import uk.co.controlz.live.utils.Event1
import uk.co.controlz.live.views.ISurfaceProvider
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CameraController(
    private val context: Context,
    private val cameraEye: CameraEye = CameraEye.LEFT,
) {
    companion object {
        private const val TAG = "Camera"
        private const val CAMERA_IMAGE_FORMAT = ImageFormat.YUV_420_888
        private const val CAMERA_SOURCE_KEY = "com.meta.extra_metadata.camera_source"
        private const val CAMERA_POSITION_KEY = "com.meta.extra_metadata.position"
    }

    val isRunning: Boolean
        get() = _isRunning.get()
    val isInitialized: Boolean
        get() = ::cameraProperties.isInitialized
    val cameraOutputSize: Size
        get() = cameraProperties.resolution
    val onCameraPropertiesChanged = Event1<CameraProperties>()

    private val cameraEyeIds = HashMap<CameraEye, String>()
    private val cameraEyeCharacteristics = HashMap<CameraEye, CameraCharacteristics>()
    private val isProcessingFrame = AtomicBoolean(false)

    private var _isRunning = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var currentImageAvailableListener: ImageAvailableListener? = null

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraProperties: CameraProperties
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageReaderThread: HandlerThread
    private lateinit var imageReaderHandler: Handler

    fun initialize() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        imageReaderThread = HandlerThread("ImageReaderThread").apply {
            start()
            imageReaderHandler = Handler(this.looper)
        }

        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cameraManager.cameraIdList.isEmpty()) {
            throw RuntimeException("Failed to get system camera")
        }

        Log.d(TAG, "Found camera ids: ${cameraManager.cameraIdList.joinToString()}")

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)

            val position =
                characteristics.get(CameraCharacteristics.Key(CAMERA_POSITION_KEY, Int::class.java))
            val eye = when (position) {
                0 -> CameraEye.LEFT
                1 -> CameraEye.RIGHT
                else -> CameraEye.UNKNOWN
            }

            cameraEyeIds[eye] = id
            cameraEyeCharacteristics[eye] = characteristics
        }

        if (cameraEyeIds[cameraEye] == null) {
            throw RuntimeException("Failed to get camera for ${cameraEye.name} eye")
        }

        cameraProperties = getCameraProperties(cameraEye)

        onCameraPropertiesChanged.invoke(cameraProperties)
    }

    fun start(
        surfaceProviders: List<ISurfaceProvider> = listOf(),
        imageAvailableListener: ImageAvailableListener? = null,
    ) {
        if (!isInitialized) {
            throw RuntimeException("Camera not initialized")
        }

        if (surfaceProviders.isEmpty() && imageAvailableListener == null) {
            Log.w(TAG, "No reason to start camera")
            return
        }

        if (_isRunning.get()) {
            Log.w(TAG, "Camera controller already running")
            return
        }

        currentImageAvailableListener = imageAvailableListener

        CoroutineScope(Dispatchers.Main).launch {
            var elapsed = 0L
            while (surfaceProviders.any { !it.surfaceAvailable }) {
                Log.w(TAG, "Waiting for the camera preview surface to become available...")

                // wait for the surface to become available
                delay(10L)
                elapsed += 10L

                if (elapsed >= 10000L) {
                    throw RuntimeException("Timeout while waiting for surface(s)")
                }
            }

            startInternal(surfaceProviders, currentImageAvailableListener)
        }
    }

    private suspend fun startInternal(
        surfaceProviders: List<ISurfaceProvider>,
        imageAvailableListener: ImageAvailableListener? = null,
    ) {
        try {
            _isRunning.set(true)

            val id = cameraEyeIds[cameraEye]
            camera = openCamera(cameraManager, id!!, cameraExecutor)

            val targets = surfaceProviders.map { it.surface!! }.toMutableList()

            if (imageAvailableListener != null) {
                Log.d(
                    TAG,
                    "Creating ImageReader with size: ${cameraOutputSize.width}x${cameraOutputSize.height}"
                )
                imageReader = ImageReader.newInstance(
                    cameraOutputSize.width,
                    cameraOutputSize.height,
                    CAMERA_IMAGE_FORMAT,
                    2,
                )
                targets.add(imageReader!!.surface)
                Log.d(TAG, "ImageReader created and surface added to targets")
            } else {
                Log.w(TAG, "No imageAvailableListener provided - ImageReader will not be created")
            }

            session = createCameraPreviewSession(camera!!, targets, cameraExecutor)

            val captureRequestBuilder =
                camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    targets.forEach { addTarget(it) }
                }

            session!!.setSingleRepeatingRequest(
                captureRequestBuilder.build(),
                cameraExecutor,
                object : CaptureCallback() {},
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    if (isProcessingFrame.get()) {
                        Log.d(TAG, "Skipping frame - still processing previous image")
                        return@setOnImageAvailableListener
                    }

                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                    isProcessingFrame.set(true)

                    imageAvailableListener?.onNewImage(image, image.width, image.height) {
                        image.close()
                        isProcessingFrame.set(false)
                    }
                },
                imageReaderHandler,
            )

            if (imageReader != null) {
                Log.d(TAG, "ImageReader listener set up successfully")
            } else {
                Log.w(TAG, "ImageReader is null - listener not set up")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            this.stop()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        executor: Executor,
    ): CameraDevice = suspendCoroutine { cont ->
        Log.d(TAG, "openCamera")

        manager.openCamera(
            cameraId,
            executor,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "camera onOpened")

                    cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "camera onDisconnected")

                    this@CameraController.stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.d(TAG, "camera onError")

                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    val ex = RuntimeException("Camera $cameraId error: ($error) $msg")
                    Log.e(TAG, ex.message, ex)

                    cont.resumeWithException(ex)
                }
            },
        )
    }

    private suspend fun createCameraPreviewSession(
        device: CameraDevice,
        targets: List<Surface>,
        executor: Executor,
    ): CameraCaptureSession = suspendCoroutine { cont ->
        Log.d(TAG, "createCameraPreviewSession")

        device.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                targets.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "CameraCaptureSession::onConfigured")

                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CameraCaptureSession::onConfigureFailed")
                        val exc =
                            RuntimeException("Camera ${device.id} session configuration failed")
                        Log.e(TAG, exc.message, exc)

                        cont.resumeWithException(exc)
                    }
                },
            )
        )
    }

    private fun getCameraProperties(eye: CameraEye): CameraProperties {
        val characteristics = cameraEyeCharacteristics[eye]!!

        val source =
            characteristics.get(CameraCharacteristics.Key(CAMERA_SOURCE_KEY, Int::class.java))
        val position =
            characteristics.get(CameraCharacteristics.Key(CAMERA_POSITION_KEY, Int::class.java))
        Log.d(TAG, "Using camera source $source with position $position")

        val formats =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats
        Log.d(TAG, "Found camera output formats: ${formats.joinToString()}")

        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(CAMERA_IMAGE_FORMAT)
        Log.d(TAG, "Found camera output sizes: ${sizes.joinToString()}")

        val translation = characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        val rotation = characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION)
        val intrinsicsArr = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val sensorSizePx =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)

        if (translation == null || rotation == null || intrinsicsArr == null || sensorSizePx == null) {
            throw RuntimeException("Failed to query camera intrinsics")
        }
        Log.d(TAG, "$sensorSizePx ${intrinsicsArr.joinToString()}} $translation $rotation")

        var quat = Quaternion(rotation[3], -rotation[0], -rotation[1], rotation[2]).inverse()
        quat = quat.times(Quaternion(180f, 0f, 0f))

        val props = CameraProperties(
            eye,
            Vector3(translation[0], translation[1], -translation[2]),
            quat,
            Vector2(intrinsicsArr[0], intrinsicsArr[1]),
            Vector2(intrinsicsArr[2], intrinsicsArr[3]),
            Size(sensorSizePx.right, sensorSizePx.bottom),
        )

        return props
    }

    fun stop() {
        Log.d(TAG, "stop")

        _isRunning.set(false)

        CoroutineScope(Dispatchers.Main).launch {
            delay(100L)
            imageReader?.close()
            imageReader = null
        }

        session?.close()
        session = null

        camera?.close()
        camera = null
    }

    fun dispose() {
        Log.d(TAG, "dispose")

        stop()

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        if (::imageReaderThread.isInitialized) {
            imageReaderThread.quitSafely()
        }
    }

    interface ImageAvailableListener {
        fun onNewImage(image: Image, width: Int, height: Int, finally: () -> Unit)
    }
}
