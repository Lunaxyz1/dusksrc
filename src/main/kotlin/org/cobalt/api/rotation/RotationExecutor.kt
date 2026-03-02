package org.cobalt.api.rotation

import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation

object RotationExecutor {

  private val mc: Minecraft =
    Minecraft.getInstance()

  private var targetYaw: Float = 0F
  private var targetPitch: Float = 0F

  private var currStrat: IRotationStrategy? = null
  private var isRotating: Boolean = false

  fun rotateTo(
    endRot: Rotation,
    strategy: IRotationStrategy,
  ) {
    if (isRotating && currStrat === strategy) {
      targetYaw = endRot.yaw
      targetPitch = endRot.pitch
      return
    }

    stopRotating()

    targetYaw = endRot.yaw
    targetPitch = endRot.pitch
    currStrat = strategy

    strategy.onStart()
    isRotating = true
  }

  fun stopRotating() {
    currStrat?.onStop()
    currStrat = null
    isRotating = false
  }

  fun isRotating(): Boolean {
    return isRotating
  }

  @SubscribeEvent
  fun onRotate(
    event: WorldRenderEvent.Last,
  ) {
    val player = mc.player ?: return

    if (!isRotating) {
      return
    }

    currStrat?.let {
      val result = it.onRotate(
        player,
        targetYaw,
        targetPitch
      )

      if (result == null) {
        stopRotating()
      } else {
        player.setYRot(AngleUtils.normalizeAngle(applyGCD(result.yaw, player.yRot)))
        player.setXRot(applyGCD(result.pitch, player.xRot, -90f, 90f).coerceIn(-90f, 90f))
      }
    }
  }

  private fun applyGCD(rotation: Float, prevRotation: Float, min: Float? = null, max: Float? = null): Float {
    val sensitivity = mc.options.sensitivity().get()
    val f = sensitivity * 0.6 + 0.2
    val gcd = f * f * f * 1.2

    val delta = AngleUtils.getRotationDelta(prevRotation, rotation)
    val roundedDelta = (delta / gcd).roundToInt() * gcd
    var result = prevRotation + roundedDelta

    if (max != null && result > max) {
      result -= gcd
    }
    if (min != null && result < min) {
      result += gcd
    }

    return result.toFloat()
  }

}
