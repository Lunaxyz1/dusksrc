package org.cobalt.internal.command

import kotlin.random.Random
import org.cobalt.api.command.Command
import org.cobalt.api.command.annotation.DefaultHandler
import org.cobalt.api.command.annotation.SubCommand
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.rotation.EasingType
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.TimedEaseStrategy
import org.cobalt.api.util.helper.Rotation
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.ui.screen.UIConfig

internal object MainCommand : Command(name = "dutt", aliases = arrayOf("cobalt", "cb")) {

  @DefaultHandler
  fun main() {
    UIConfig.openUI()
  }

  @SubCommand
  fun rotate(yaw: Double, pitch: Double, duration: Int) {
    RotationExecutor.rotateTo(
      Rotation(yaw.toFloat(), pitch.toFloat()),
      TimedEaseStrategy(
        yawEasing = EasingType.EASE_OUT_EXPO,
        pitchEasing = EasingType.EASE_OUT_EXPO,
        duration = duration.toLong()
      )
    )
  }

  @SubCommand
  fun rotate() {
    val yaw = Random.nextFloat() * 360f - 180f
    val pitch = Random.nextFloat() * 180f - 90f

    RotationExecutor.rotateTo(
      Rotation(yaw, pitch),
      TimedEaseStrategy(
        yawEasing = EasingType.EASE_OUT_EXPO,
        pitchEasing = EasingType.EASE_OUT_EXPO,
        duration = 400L
      )
    )
  }

  @SubCommand
  fun start(x: Double, y: Double, z: Double) {
    PathfindingModule.startTo(x, y, z)
  }

  @SubCommand
  fun stop() {
    PathfindingModule.stopPath()
  }

  @SubCommand
  fun pathstart() {
    PathfindingModule.startFromSettings()
  }

  @SubCommand
  fun pathstop() {
    PathfindingModule.stopPath()
  }

  @SubCommand
  fun setpos(x: Double, y: Double, z: Double) {
    PathfindingModule.setTargetOnly(x, y, z)
  }

  @SubCommand
  fun setposhere() {
    PathfindingModule.setTargetAtPlayer()
  }

  @SubCommand
  fun notification(title: String, description: String) {
    NotificationManager.queue(title, description, 2000L)
  }

}
