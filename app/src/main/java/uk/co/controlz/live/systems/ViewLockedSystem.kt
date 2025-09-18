package uk.co.controlz.live.systems

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import uk.co.controlz.live.camera.CameraProperties
import uk.co.controlz.live.utils.math.MathUtils
import uk.co.controlz.live.ViewLocked

class ViewLockedSystem(private var fov: Float = 72f) : SystemBase() {
    companion object {
        private const val TAG: String = "ViewLockedSystem"
    }

    private var viewLockedEntities = HashMap<Long, ViewLockedInfo>()

    override fun execute() {
        findNewEntities()
        processEntities()
    }

    private fun findNewEntities() {
        val q = Query.Companion.where {
            has(ViewLocked.Companion.id, Transform.Companion.id) and changed(
                ViewLocked.Companion.id
            )
        }
        for (entity in q.eval()) {
            if (viewLockedEntities.contains(entity.id)) {
                continue
            }

            val completable =
                systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity) ?: continue

            completable.thenAccept { sceneObject ->
                val viewLockedComp = entity.getComponent<ViewLocked>()

                var distance = 0f
                var panelWidth = 0f

                if (entity.hasComponent<Panel>() && viewLockedComp.fillView) {
                    val panelSceneObject = sceneObject as PanelSceneObject
                    val shapeConfig = panelSceneObject.getPanelShapeConfig()

                    panelWidth = shapeConfig!!.width
                    distance = MathUtils.panelDistanceForSize(fov, shapeConfig.width)
                }

                viewLockedEntities[entity.id] = ViewLockedInfo(entity, panelWidth, distance)
            }
        }
    }

    private fun processEntities() {
        val headPose = getScene().getViewerPose()

        viewLockedEntities.forEach { (_, info) ->
            val viewLockedComp = info.entity.getComponent<ViewLocked>()

            val quat =
                Quaternion(
                    viewLockedComp.rotation.x,
                    viewLockedComp.rotation.y,
                    viewLockedComp.rotation.z,
                )
            val newPose = headPose.times(Pose(viewLockedComp.position, quat))
            newPose.t += newPose.forward() * info.distance

            info.entity.setComponent(Transform(newPose))
        }
    }

    fun onCameraPropertiesChanged(properties: CameraProperties) {
        fov = properties.fov

        viewLockedEntities.forEach { (_, info) ->
            val viewLockedComp = info.entity.getComponent<ViewLocked>()

            if (viewLockedComp.fillView) {
                val offsetPose = properties.getHeadToCameraPose()
                viewLockedComp.position = offsetPose.t
                viewLockedComp.rotation = offsetPose.q.toEuler()

                info.entity.setComponent(viewLockedComp)
                info.distance = MathUtils.panelDistanceForSize(fov, info.panelWidth)
            }
        }
    }

    private data class ViewLockedInfo(
        val entity: Entity,
        val panelWidth: Float,
        var distance: Float
    )
}