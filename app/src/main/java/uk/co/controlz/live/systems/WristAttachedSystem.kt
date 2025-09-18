package uk.co.controlz.live.systems

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

import uk.co.controlz.live.HandSide
import uk.co.controlz.live.utils.math.QuaternionUtils.fromSequentialPYR
import uk.co.controlz.live.WristAttached

class WristAttachedSystem : SystemBase() {
    companion object {
        private const val TAG: String = "WristAttachedSystem"
    }

    private val wristAttachedEntities = mutableListOf<Entity>()

    override fun execute() {
        findNewEntities()

        val playerBody = getAvatarBody()
        if (!playerBody.head.hasComponent<Transform>() || !playerBody.leftHand.hasComponent<Transform>() || !playerBody.rightHand.hasComponent<Transform>()) {
            return
        }

        val headTransform = playerBody.head.getComponent<Transform>()
        val leftHandTransform = playerBody.leftHand.getComponent<Transform>()
        val rightHandTransform = playerBody.rightHand.getComponent<Transform>()

        playerBody.leftHand.setComponent(Visible(false))
        playerBody.rightHand.setComponent(Visible(false))

        for (entity in wristAttachedEntities) {
            val comp = entity.getComponent<WristAttached>()

            val handTransform = when (comp.side) {
                HandSide.LEFT -> leftHandTransform
                HandSide.RIGHT -> rightHandTransform
            }

            val quatOffset =
                Quaternion.fromSequentialPYR(comp.rotation.x, comp.rotation.y, comp.rotation.z)
            val rotation = handTransform.transform.q.times(quatOffset)

            val position = handTransform.transform.t + rotation.times(comp.position)

            val pose = Pose(position, if (comp.faceUser) headTransform.transform.q else rotation)
            entity.setComponent(Transform(pose))

            val vHeadFwd = headTransform.transform.forward()
            val vAnchorFwd = rotation.times(Vector3.Forward)
            val vHeadToAnchor = (position - headTransform.transform.t).normalize()

            val lookingAtHand = vHeadFwd.dot(vHeadToAnchor) > 0.85f
            val handFacingHead = vAnchorFwd.dot(vHeadToAnchor) > 0.4f
            entity.setComponent(Visible(lookingAtHand && handFacingHead))
        }
    }

    override fun delete(entity: Entity) {
        super.delete(entity)

        wristAttachedEntities.remove(entity)
    }

    private fun findNewEntities() {
        val query =
            Query.where { has(WristAttached.id, Transform.id) and changed(WristAttached.id) }
        for (entity in query.eval()) {
            if (wristAttachedEntities.contains(entity)) {
                continue
            }

            if (!entity.isLocal()) {
                continue
            }

            wristAttachedEntities.add(entity)
        }
    }
    
    private fun getAvatarBody(): AvatarBody {
        return Query.where { has(AvatarBody.id) }.eval()
            .filter { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }.first()
            .getComponent<AvatarBody>()
    }
}
