package uk.co.controlz.live.activities

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SendRate
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkFeature
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature

import java.io.File

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import uk.co.controlz.live.systems.WristAttachedSystem
import uk.co.controlz.live.camera.CameraStatus
import uk.co.controlz.live.camera.CameraFeature
import uk.co.controlz.live.R
import uk.co.controlz.live.services.SettingsService
import uk.co.controlz.live.WristAttached

class MainActivity : ActivityCompat.OnRequestPermissionsResultCallback, AppSystemActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 1000
        private val PERMISSIONS_REQUIRED = arrayOf(
            "horizonos.permission.HEADSET_CAMERA", "android.permission.RECORD_AUDIO"
        )
    }

    private val activityScope = CoroutineScope(Dispatchers.Main)

    private var gltfxEntity: Entity? = null
    private var cameraControlsBtn: ImageButton? = null

    private lateinit var permissionsResultCallback: (granted: Boolean) -> Unit
    private lateinit var cameraFeature: CameraFeature

    override fun registerFeatures(): List<SpatialFeature> {
        cameraFeature = CameraFeature(
            activity = this,
            onStatusChanged = ::onObjectDetectionFeatureStatusChanged,
            spawnCameraViewPanel = true,
            enableGeminiLive = true,
            geminiApiKey = null,
            onGeminiResponse = ::onGeminiResponse,
        )

        return listOf(
            VRFeature(this),
            ComposeFeature(),
            cameraFeature,
            IsdkFeature(this, spatial, systemManager),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SettingsService.initialize(this)

        NetworkedAssetLoader.init(
            File(applicationContext.cacheDir.canonicalPath), OkHttpAssetFetcher()
        )

        systemManager.unregisterSystem<LocomotionSystem>()

        componentManager.registerComponent<WristAttached>(WristAttached.Companion, SendRate.DEFAULT)
        systemManager.registerSystem(WristAttachedSystem())

        loadGLXF().invokeOnCompletion {
            glXFManager.getGLXFInfo("gemini_app_main_scene")
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()

        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(0f),
            sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
            environmentIntensity = 0.2f,
        )

        scene.enablePassthrough(true)

        scene.setViewOrigin(0.0f, 0.0f, 0.0f, 180.0f)
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            PanelRegistration(R.layout.ui_camera_controls_view) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInDp = 80f
                    width = 0.04f
                    height = 0.04f
                    layerConfig = LayerConfig()
                    enableTransparent = true
                }
                panel {
                    cameraControlsBtn = rootView?.findViewById(R.id.camera_play_btn)
                        ?: throw RuntimeException("Missing camera play/pause button")

                    cameraControlsBtn?.setOnClickListener {
                        when (cameraFeature.status) {
                            CameraStatus.PAUSED -> {
                                if (!hasPermissions()) {
                                    this@MainActivity.requestPermissions { granted ->
                                        if (granted) {
                                            startScanning()
                                        }
                                    }
                                    return@setOnClickListener
                                }

                                startScanning()
                            }
                            CameraStatus.SCANNING -> {
                                stopScanning()
                            }
                        }
                    }
                }
            },
        )
    }

    private fun startScanning() {
        cameraFeature.scan()
    }

    private fun stopScanning() {
        cameraFeature.pause()
    }

    private fun onObjectDetectionFeatureStatusChanged(newStatus: CameraStatus) {
        cameraControlsBtn?.setBackgroundResource(
            when (newStatus) {
                CameraStatus.PAUSED -> com.meta.spatial.uiset.R.drawable.ic_play_circle_24
                CameraStatus.SCANNING -> com.meta.spatial.uiset.R.drawable.ic_pause_circle_24
            }
        )
    }

    private fun onGeminiResponse(response: String) {
        Log.i(TAG, "Gemini response: $response")
    }

    override fun onPause() {
        stopScanning()
        super.onPause()
    }

    private fun loadGLXF(): Job {
        gltfxEntity = Entity.create()
        return activityScope.launch {
            glXFManager.inflateGLXF(
                "apk:///scenes/Composition.glxf".toUri(),
                rootEntity = gltfxEntity!!,
                keyName = "gemini_app_main_scene",
            )
        }
    }

    private fun hasPermissions() = PERMISSIONS_REQUIRED.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions(callback: (granted: Boolean) -> Unit) {
        permissionsResultCallback = callback

        ActivityCompat.requestPermissions(this, PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.d(TAG, "Camera permission granted")
                    permissionsResultCallback(true)
                } else {
                    Log.w(TAG, "Camera permission denied")
                    permissionsResultCallback(false)
                }
            }
        }
    }
}
