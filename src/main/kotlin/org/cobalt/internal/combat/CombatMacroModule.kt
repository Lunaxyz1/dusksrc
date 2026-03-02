package org.cobalt.internal.combat

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.TrackingRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.internal.pathfinding.DuskPathfinder
import org.cobalt.internal.pathfinding.PathfindingModule

object CombatMacroModule : Module("Combat Macro") {

  private val mc: Minecraft = Minecraft.getInstance()
  private val rotationStrategy = TrackingRotationStrategy(16f, 12f)

  private val enabled = CheckboxSetting(
    "Enabled",
    "Pathfind to a target and attack until stopped.",
    false
  )

  private val info = InfoSetting(
    "Target",
    "Set a target name (partial match). Leave blank to target nearest mob.",
    InfoType.INFO
  )

  private val targetName = TextSetting(
    "Target Name",
    "Entity name to target (partial match).",
    ""
  )

  private val searchRange = SliderSetting(
    "Search Range",
    "Max distance to search for targets.",
    32.0,
    8.0,
    96.0
  )

  private val minCps = SliderSetting(
    "Min CPS",
    "Minimum clicks per second.",
    6.0,
    1.0,
    20.0
  )

  private val maxCps = SliderSetting(
    "Max CPS",
    "Maximum clicks per second.",
    10.0,
    1.0,
    20.0
  )

  private val attackRange = SliderSetting(
    "Attack Range",
    "Distance to start attacking.",
    3.2,
    2.0,
    6.0
  )

  private val stuckTicksSetting = SliderSetting(
    "Stuck Ticks",
    "Ticks without movement before warp hub.",
    80.0,
    20.0,
    200.0
  )

  private val warpOnStuck = CheckboxSetting(
    "Warp Hub On Stuck",
    "Warp to hub if stuck while macro is active.",
    true
  )

  private val requireLos = CheckboxSetting(
    "Require LOS",
    "Only attack when you have line of sight.",
    true
  )

  private val aimTolerance = SliderSetting(
    "Aim Tolerance",
    "Max yaw/pitch error before attacking.",
    15.0,
    4.0,
    45.0
  )

  private var lastTargetPos: BlockPos? = null
  private var lastMoveX = 0.0
  private var lastMoveY = 0.0
  private var lastMoveZ = 0.0
  private var stuckTicks = 0
  private var nextAttackNs = 0L

  init {
    addSetting(
      enabled,
      info,
      targetName,
      searchRange,
      minCps,
      maxCps,
      attackRange,
      stuckTicksSetting,
      warpOnStuck,
      requireLos,
      aimTolerance
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val player = mc.player ?: return
    if (!enabled.value) {
      stopMacro()
      return
    }

    if (player.isDeadOrDying || player.health <= 0f) {
      stopMacro()
      return
    }

    val level = mc.level ?: return
    val target = findTarget(player) ?: run {
      if (DuskPathfinder.isActive()) {
        DuskPathfinder.stop(mc, "No target found.")
      }
      return
    }

    val dist = player.distanceTo(target)
    val inRange = dist <= attackRange.value
    if (inRange) {
      val rotation = AngleUtils.getRotation(target)
      RotationExecutor.rotateTo(rotation, rotationStrategy)
    } else {
      RotationExecutor.stopRotating()
    }

    if (inRange) {
      if (DuskPathfinder.isActive()) {
        DuskPathfinder.stop(mc, "Target in range.")
      }
      attemptAttack(player, target)
    } else {
      if (!PathfindingModule.enabled.value) {
        DuskPathfinder.tick(mc)
      }
      val targetPos = target.blockPosition()
      val last = lastTargetPos
      if (!DuskPathfinder.isActive() || last == null || last.distSqr(targetPos) > 4.0) {
        DuskPathfinder.start(mc, targetPos)
        lastTargetPos = targetPos
      }
    }

    updateStuck(player, inRange)
  }

  private fun findTarget(player: Player): LivingEntity? {
    val level = mc.level ?: return null
    val searchRangeSq = searchRange.value * searchRange.value
    val filter = targetName.value.trim().lowercase()

    var best: LivingEntity? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (entity in level.entitiesForRendering()) {
      val living = entity as? LivingEntity ?: continue
      if (living is ArmorStand) continue
      if (living is Player) continue
      if (living == player) continue
      if (!living.isAlive) continue
      val dx = living.x - player.x
      val dy = living.y - player.y
      val dz = living.z - player.z
      val distSq = dx * dx + dy * dy + dz * dz
      if (distSq > searchRangeSq) continue
      if (filter.isNotEmpty() && !living.name.string.lowercase().contains(filter)) continue
      if (distSq < bestDist) {
        best = living
        bestDist = distSq
      }
    }
    return best
  }

  private fun attemptAttack(player: Player, target: LivingEntity) {
    if (!isAttackReady(player, target)) return

    val now = System.nanoTime()
    if (now < nextAttackNs) return

    val minRate = min(minCps.value, maxCps.value).coerceAtLeast(0.1)
    val maxRate = max(minCps.value, maxCps.value).coerceAtLeast(minRate)
    val cps = minRate + (Math.random() * (maxRate - minRate))
    val delayNs = (1_000_000_000.0 / cps).toLong()

    nextAttackNs = now + delayNs
    mc.gameMode?.attack(player, target)
    player.swing(InteractionHand.MAIN_HAND)
  }

  private fun isAttackReady(player: Player, target: LivingEntity): Boolean {
    val cooldown = player.getAttackStrengthScale(0.0f)
    if (cooldown < 0.9f) return false
    if (requireLos.value && !player.hasLineOfSight(target)) return false

    val rotation = AngleUtils.getRotation(target)
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val pitchError = abs(rotation.pitch - player.xRot)
    val tolerance = aimTolerance.value
    if (yawError > tolerance || pitchError > tolerance) return false

    return true
  }

  private fun updateStuck(player: Player, inRange: Boolean) {
    if (inRange) {
      stuckTicks = 0
      lastMoveX = player.x
      lastMoveY = player.y
      lastMoveZ = player.z
      return
    }

    val dx = player.x - lastMoveX
    val dy = player.y - lastMoveY
    val dz = player.z - lastMoveZ
    val moved = dx * dx + dy * dy + dz * dz > 0.0008
    if (moved) {
      stuckTicks = 0
      lastMoveX = player.x
      lastMoveY = player.y
      lastMoveZ = player.z
      return
    }

    if (DuskPathfinder.isActive()) {
      stuckTicks++
    } else {
      stuckTicks = 0
    }

    if (stuckTicks >= stuckTicksSetting.value.toInt()) {
      stuckTicks = 0
      if (warpOnStuck.value) {
        (player as? LocalPlayer)?.connection?.sendCommand("warp hub")
        ChatUtils.sendMessage("Combat macro stuck. Warping to hub.")
        stopMacro()
      }
    }
  }

  private fun stopMacro() {
    if (DuskPathfinder.isActive()) {
      DuskPathfinder.stop(mc, "Combat macro stopped.")
    }
    RotationExecutor.stopRotating()
    lastTargetPos = null
    stuckTicks = 0
    nextAttackNs = 0L
  }
}
