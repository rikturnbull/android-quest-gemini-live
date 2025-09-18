package uk.co.controlz.live.utils.math

import android.graphics.PointF
import android.graphics.Rect

import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object MathUtils {
  const val EPSILON = 1e-9
  const val DEG_TO_RAD = PI / 180f

  fun panelDistanceForSize(fov: Float, size: Float): Float {
    val rad = fov * DEG_TO_RAD
    val distance = (size / 2f) / tan(rad / 2f)
    return distance.toFloat()
  }

  fun rayPlaneIntersection(ray: Ray, plane: Plane): Vector3? {
    if (
        plane.normal.lengthSquared() < EPSILON * EPSILON ||
            ray.direction.lengthSquared() < EPSILON * EPSILON
    ) {
      return null
    }

    val denom = plane.normal.dot(ray.direction)

    if (abs(denom) < EPSILON) {
      return null
    }

    val originToPlanePoint = plane.point - ray.origin
    val t = plane.normal.dot(originToPlanePoint) / denom

    if (t < -EPSILON) {
      return null
    }

    val intersectionPoint = ray.origin + ray.direction * t
    return intersectionPoint
  }

  fun Vector3.lengthSquared(): Float {
    return this.dot(this)
  }

  fun Vector3.copy(other: Vector3): Vector3 {
    this.x = other.x
    this.y = other.y
    this.z = other.z
    return this // for chaining
  }

  fun PointF.toVector2(): Vector2 {
    return Vector2(this.x, this.y)
  }

  fun Rect.intersection(other: Rect, result: Rect): Boolean {
    val intersectLeft = max(this.left, other.left)
    val intersectTop = max(this.top, other.top)
    val intersectRight = min(this.right, other.right)
    val intersectBottom = min(this.bottom, other.bottom)

    val intersection = Rect(intersectLeft, intersectTop, intersectRight, intersectBottom)
    result.copy(intersection)

    return !result.isEmpty
  }

  fun Rect.copy(other: Rect) {
    this.left = other.left
    this.top = other.top
    this.right = other.right
    this.bottom = other.bottom
  }

  fun Rect.area(): Int {
    return this.width() * this.height()
  }
}
