package uk.co.controlz.live.utils.math

import com.meta.spatial.core.Bound3D
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object QuaternionUtils {
    fun Quaternion.Companion.fromAxisAngle(axis: Vector3, angleDegrees: Float): Quaternion {
        val angleRadians = angleDegrees * PI / 180f
        val halfAngle = angleRadians / 2
        val sinHalfAngle = sin(halfAngle).toFloat()

        return Quaternion(
            cos(halfAngle).toFloat(),
            axis.x * sinHalfAngle,
            axis.y * sinHalfAngle,
            axis.z * sinHalfAngle,
        ).normalize()
    }

    fun Quaternion.Companion.fromSequentialPYR(
        pitchDeg: Float,
        yawDeg: Float,
        rollDeg: Float,
    ): Quaternion {
        return Quaternion.Companion.fromAxisAngle(Vector3.Companion.Right, pitchDeg)
            .times(Quaternion.Companion.fromAxisAngle(Vector3.Companion.Up, yawDeg))
            .times(Quaternion.Companion.fromAxisAngle(Vector3.Companion.Forward, rollDeg))
            .normalize()
    }

    fun Bound3D.isValid(): Boolean {
        return this.min.x.isFinite() &&
                this.min.y.isFinite() &&
                this.min.z.isFinite() &&
                this.max.x.isFinite() &&
                this.max.y.isFinite() &&
                this.max.z.isFinite()
    }
}