package org.cobalt.api.rotation.strategy

import kotlin.math.abs
import kotlin.math.min
import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.rotation.IRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation

class BezierTrackingRotationStrategy(
  private val maxYawStep: Float = 12f,
  private val maxPitchStep: Float = 10f,
  private val curveIn: Float = 0.12f,
  private val curveOut: Float = 0.88f,
  private val minScale: Float = 0.25f,
  private val snapThreshold: Float = 0.35f,
) : IRotationStrategy {

  override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation? {
    val yawDelta = AngleUtils.getRotationDelta(player.yRot, targetYaw)
    val pitchDelta = targetPitch - player.xRot
    val nextYaw = player.yRot + smoothStep(yawDelta, maxYawStep)
    val nextPitch = (player.xRot + smoothStep(pitchDelta, maxPitchStep)).coerceIn(-90f, 90f)
    return Rotation(nextYaw, nextPitch)
  }

  private fun smoothStep(delta: Float, maxStep: Float): Float {
    val absDelta = abs(delta)
    if (absDelta < snapThreshold) {
      return delta
    }
    val stepLimit = min(absDelta, maxStep)
    val t = (absDelta / maxStep).coerceIn(0f, 1f)
    val eased = cubicBezier(t, curveIn, curveOut)
    val scale = minScale + (1f - minScale) * eased
    val step = stepLimit * scale
    return if (delta < 0f) -step else step
  }

  private fun cubicBezier(t: Float, p1: Float, p2: Float): Float {
    val inv = 1f - t
    return (3f * inv * inv * t * p1) + (3f * inv * t * t * p2) + (t * t * t)
  }
}
