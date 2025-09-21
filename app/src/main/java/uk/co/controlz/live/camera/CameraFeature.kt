package uk.co.controlz.live.camera

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView

import com.meta.spatial.core.ComponentRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SendRate
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform

import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

import uk.co.controlz.live.views.CameraPreviewSurface
import uk.co.controlz.live.views.ISurfaceProvider
import uk.co.controlz.live.BuildConfig
import uk.co.controlz.live.gemini.GeminiAudioPlayer
import uk.co.controlz.live.gemini.GeminiImageListener
import uk.co.controlz.live.gemini.GeminiLiveStreamer
import uk.co.controlz.live.R
import uk.co.controlz.live.systems.ViewLockedSystem
import uk.co.controlz.live.ViewLocked

class CameraFeature(
    private val activity: AppSystemActivity,
    private val onStatusChanged: ((CameraStatus) -> Unit)? = null,
    private val spawnCameraViewPanel: Boolean = true,
    private val enableGeminiLive: Boolean = true,
    private val geminiApiKey: String? = null,
    private val onGeminiResponse: ((String) -> Unit)? = null,
) : SpatialFeature, CameraController.ImageAvailableListener {
    companion object {
        private const val TAG = "CameraFeature"
    }

    private val cameraController: CameraController

    private var geminiStreamer: GeminiLiveStreamer? = null
    private var geminiImageListener: GeminiImageListener? = null
    private var geminiAudioPlayer: GeminiAudioPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var cameraStatusRootView: View? = null
    private var cameraStatusText: TextView? = null
    private var _cameraStatus: CameraStatus = CameraStatus.PAUSED
    private var cameraPreviewView: CameraPreviewSurface? = null

    private lateinit var cameraStatusEntity: Entity
    private lateinit var viewLockedSystem: ViewLockedSystem
    private lateinit var cameraViewEntity: Entity

    val status: CameraStatus
        get() = _cameraStatus

    val isGeminiActive: Boolean
        get() = geminiStreamer?.isActive() ?: false

    init {
        cameraController = CameraController(activity)
        cameraController.onCameraPropertiesChanged += ::onCameraPropertiesChanged

        if (enableGeminiLive) {
            val apiKey = geminiApiKey ?: BuildConfig.GEMINI_API_KEY
            val model = R.string.gemini_model

            if (apiKey.isNotEmpty() && apiKey != "\"\"" && apiKey != "null") {
                geminiAudioPlayer = GeminiAudioPlayer()

                geminiStreamer = GeminiLiveStreamer(apiKey, activity).apply {
                    onGeminiResponse = this@CameraFeature.onGeminiResponse
                    onGeminiAudioResponse = { audioData ->
                        geminiAudioPlayer?.playAudio(audioData)
                    }
                    onConnectionStatusChanged = { isConnected ->
                        Log.i(
                            TAG,
                            "Gemini connection status: ${if (isConnected) "Connected" else "Disconnected"}"
                        )
                    }
                    onSetupCompleted = {
                        Log.i(TAG, "Gemini setup completed - audio recording should now be active")
                    }
                }
                geminiImageListener = GeminiImageListener(geminiStreamer!!)
                Log.i(TAG, "Gemini Live integration enabled with audio playback")
            } else {
                Log.w(TAG, "Gemini Live enabled but no API key provided")
            }
        } else {
            Log.i(TAG, "Gemini Live integration disabled")
        }
    }

    fun toggleGeminiAudioRecording(): Boolean {
        geminiStreamer?.let { streamer ->
            return if (streamer.isActive()) {
                if (streamer.startAudioRecording()) {
                    Log.i(TAG, "Gemini audio recording started")
                    true
                } else {
                    Log.w(TAG, "Failed to start Gemini audio recording")
                    false
                }
            } else {
                Log.w(TAG, "Cannot start audio recording - Gemini not connected")
                false
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activity.registerPanel(
            PanelRegistration(R.layout.ui_camera_view) {
                val cameraOutputSize = cameraController.cameraOutputSize

                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInPx = cameraOutputSize.width
                    layoutHeightInPx = cameraOutputSize.height
                    width =
                        cameraOutputSize.width / (PanelConfigOptions.Companion.EYEBUFFER_WIDTH * 0.5f)
                    height =
                        cameraOutputSize.height / (PanelConfigOptions.Companion.EYEBUFFER_HEIGHT * 0.5f)
                    layerConfig = LayerConfig()
                    enableTransparent = true
                }
                panel {
                    cameraPreviewView = rootView?.findViewById(R.id.preview_view)
                    if (cameraPreviewView?.visibility == View.GONE) {
                        cameraPreviewView = null
                    }

                    this@CameraFeature.scan()
                }
            })

        activity.registerPanel(
            PanelRegistration(R.layout.ui_camera_status_view) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInDp = 100f
                    width = 0.1f
                    height = 0.05f
                    layerConfig = LayerConfig()
                    enableTransparent = true
                }
                panel {
                    cameraStatusRootView = rootView
                    cameraStatusText = rootView?.findViewById(R.id.camera_status)
                        ?: throw RuntimeException("Missing camera status text view")
                }
            })
    }

    override fun systemsToRegister(): List<SystemBase> {
        val systems = mutableListOf<SystemBase>()

        viewLockedSystem = ViewLockedSystem()
        cameraController.onCameraPropertiesChanged += viewLockedSystem::onCameraPropertiesChanged
        systems.add(viewLockedSystem)

        return systems
    }

    override fun componentsToRegister(): List<ComponentRegistration> {
        return listOf(
            ComponentRegistration.Companion.createConfig<ViewLocked>(
                ViewLocked.Companion, SendRate.DEFAULT
            ),
        )
    }

    override fun onSceneReady() {
        cameraStatusEntity = Entity.Companion.createPanelEntity(
            R.layout.ui_camera_status_view,
            Transform(),
            Hittable(MeshCollision.NoCollision),
            ViewLocked(Vector3(-0.16f, 0.15f, 0.7f), Vector3(0f), false),
        )
    }

    fun scan() {
        if (cameraController.isInitialized) {
            geminiStreamer?.let { streamer ->
                scope.launch {
                    val connected = streamer.connect()
                    if (connected) {
                        Log.i(TAG, "Gemini Live connection established")
                    } else {
                        Log.w(TAG, "Failed to establish Gemini Live connection")
                    }
                }
            }

            val imageListeners = mutableListOf<CameraController.ImageAvailableListener>()

            imageListeners.add(this)

            geminiImageListener?.let { imageListeners.add(it) }

            cameraController.start(
                surfaceProviders = listOfNotNull(cameraPreviewView as? ISurfaceProvider),
                imageAvailableListener = if (imageListeners.size == 1) {
                    imageListeners[0]
                } else {
                    object : CameraController.ImageAvailableListener {
                        override fun onNewImage(
                            image: Image, width: Int, height: Int, finally: () -> Unit
                        ) {
                            var finallyCalled = false
                            val finallyWrapper = {
                                if (!finallyCalled) {
                                    finallyCalled = true
                                    finally()
                                }
                            }

                            imageListeners.forEachIndexed { index, listener ->
                                if (index == imageListeners.size - 1) {
                                    listener.onNewImage(image, width, height, finallyWrapper)
                                } else {
                                    listener.onNewImage(image, width, height) { }
                                }
                            }
                        }
                    }
                })
            updateCameraStatus(CameraStatus.SCANNING)
            return
        }

        cameraController.initialize()
    }

    fun pause(immediate: Boolean = false) {
        if (!cameraController.isInitialized || !cameraController.isRunning) {
            return
        }

        cameraController.stop()
        updateCameraStatus(CameraStatus.PAUSED)

        geminiStreamer?.close()
        geminiAudioPlayer?.stop()
    }

    private fun onCameraPropertiesChanged(properties: CameraProperties) {
        if (!spawnCameraViewPanel) {
            scan()
            return
        }

        if (::cameraViewEntity.isInitialized) {
            return
        }

        val offsetPose = properties.getHeadToCameraPose()
        cameraViewEntity = Entity.Companion.createPanelEntity(
            R.layout.ui_camera_view,
            Transform(),
            Hittable(MeshCollision.NoCollision),
            ViewLocked(offsetPose.t, offsetPose.q.toEuler(), true),
        )
    }

    private fun updateCameraStatus(newStatus: CameraStatus) {
        if (_cameraStatus == newStatus) {
            return
        }

        when (newStatus) {
            CameraStatus.PAUSED -> {
                cameraStatusText?.setText(R.string.camera_status_off)
            }

            CameraStatus.SCANNING -> {
                cameraStatusText?.setText(R.string.camera_status_on)
            }
        }

        cameraStatusRootView?.let {
            val durationMs = 250L
            val kf0 = Keyframe.ofFloat(0f, 1f)
            val kf1 = Keyframe.ofFloat(0.5f, 1.5f)
            val kf2 = Keyframe.ofFloat(1f, 1f)
            val pvhScaleX = PropertyValuesHolder.ofKeyframe("scaleX", kf0, kf1, kf2)
            val pvhScaleY = PropertyValuesHolder.ofKeyframe("scaleY", kf0, kf1, kf2)
            ObjectAnimator.ofPropertyValuesHolder(it, pvhScaleX, pvhScaleY).apply {
                duration = durationMs
                start()
            }
        }

        _cameraStatus = newStatus
        onStatusChanged?.invoke(newStatus)
    }

    override fun onNewImage(image: Image, width: Int, height: Int, finally: () -> Unit) {
        finally()
    }

    override fun onPauseActivity() {
        pause()
        super.onPauseActivity()
    }

    override fun onDestroy() {
        pause(true)
        cameraController.dispose()

        geminiStreamer?.close()
        geminiImageListener?.dispose()
        geminiAudioPlayer?.dispose()
        scope.cancel()

        if (::cameraViewEntity.isInitialized) {
            cameraViewEntity.destroy()
        }
        if (::cameraStatusEntity.isInitialized) {
            cameraStatusEntity.destroy()
        }
        super.onDestroy()
    }
}