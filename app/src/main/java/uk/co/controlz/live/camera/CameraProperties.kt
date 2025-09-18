package uk.co.controlz.live.camera

import android.util.Size
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import uk.co.controlz.live.utils.math.MathUtils
import uk.co.controlz.live.utils.math.Plane
import uk.co.controlz.live.utils.math.Ray

class CameraProperties(
    val eye: CameraEye,
    val translation: Vector3,
    val rotation: Quaternion,
    val focalLength: Vector2,
    val principalPoint: Vector2,
    val resolution: Size,
) {
    val fov: Float

    init {
        val left = Vector2(0f, resolution.height.toFloat() / 2f)
        val right = Vector2(resolution.width.toFloat(), resolution.height.toFloat() / 2f)
        val leftSidePointInCamera = screenPointToRayInCamera(left)
        val rightSidePointInCamera = screenPointToRayInCamera(right)
        fov = leftSidePointInCamera.angleBetweenDegrees(rightSidePointInCamera)
    }

    fun getHeadToCameraPose(): Pose {
        return Pose(translation, rotation)
    }

    fun screenPointToRayInCamera(screenPoint: Vector2): Vector3 {
        val direction =
            Vector3(
                x = (screenPoint.x - principalPoint.x) / focalLength.x,
                y = ((resolution.height - screenPoint.y) - principalPoint.y) / focalLength.y,
                z = 1f,
            )
                .normalize()
        return direction
    }

    fun screenPointToPointOnViewPlane(screenPoint: Vector2, viewDistance: Float): Vector3 {
        val viewPlane = Plane(Vector3.Companion.Forward * viewDistance, -Vector3.Companion.Forward)
        val direction = screenPointToRayInCamera(screenPoint)
        val intersection = MathUtils.rayPlaneIntersection(Ray(Vector3(0f), direction), viewPlane)
        return intersection!!
    }
}