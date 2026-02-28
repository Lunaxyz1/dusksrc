package org.cobalt.internal.pathfinding

import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.ui.NVGRenderer

object PathfindingModule : Module("Pathfinding") {

  private val mc: Minecraft = Minecraft.getInstance()

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable pathfinding target selection and commands.",
    false
  )

  private val info = InfoSetting(
    "Target",
    "Use /cobalt setpos or /cobalt setposhere to set the target.",
    InfoType.INFO
  )

  val targetX = TextSetting("Target X", "Target X coordinate.", "0")
  val targetY = TextSetting("Target Y", "Target Y coordinate.", "0")
  val targetZ = TextSetting("Target Z", "Target Z coordinate.", "0")
  val targetBlock = TextSetting("Target Block", "Filled from right-click (informational).", "")

  val cacheHudEnabled = CheckboxSetting(
    "Cache HUD",
    "Show cached chunk map HUD.",
    false
  )

  val cacheHudRadius = SliderSetting(
    "Cache Radius",
    "Chunk radius shown in the cache HUD.",
    4.0,
    1.0,
    12.0
  )

  val cacheHudCellSize = SliderSetting(
    "Cache Cell Size",
    "Cell size for the cache HUD (pixels).",
    8.0,
    4.0,
    16.0
  )

  val cacheHudShowGrid = CheckboxSetting(
    "Cache Grid",
    "Show grid lines on the cache HUD.",
    true
  )

  private val startAction = ActionSetting(
    "Start",
    "Start pathfinding to the target coordinates.",
    "Start"
  ) {
    startFromSettings()
  }

  private val stopAction = ActionSetting(
    "Stop",
    "Stop the current path.",
    "Stop"
  ) {
    stopPath()
  }

  val cacheHud = hudElement(
    "path-cache-hud",
    "Cache Map",
    "Shows cached chunks around you."
  ) {
    anchor = HudAnchor.TOP_RIGHT
    offsetX = -12f
    offsetY = 12f

    width {
      val radius = cacheHudRadius.value.toInt().coerceAtLeast(1)
      val cell = cacheHudCellSize.value.toFloat()
      (radius * 2 + 1) * cell + 8f
    }
    height {
      val radius = cacheHudRadius.value.toInt().coerceAtLeast(1)
      val cell = cacheHudCellSize.value.toFloat()
      (radius * 2 + 1) * cell + 8f
    }

    render { screenX, screenY, _ ->
      if (!cacheHudEnabled.value) return@render
      val player = mc.player ?: return@render
      val level = mc.level ?: return@render

      val radius = cacheHudRadius.value.toInt().coerceAtLeast(1)
      val cell = cacheHudCellSize.value.toFloat()
      val size = radius * 2 + 1
      val mapW = size * cell
      val mapH = size * cell

      NVGRenderer.rect(screenX, screenY, mapW + 8f, mapH + 8f, ThemeManager.currentTheme.panel, 6f)

      val originX = screenX + 4f
      val originY = screenY + 4f
      val centerChunkX = player.blockX shr 4
      val centerChunkZ = player.blockZ shr 4

      for (dz in -radius..radius) {
        for (dx in -radius..radius) {
          val chunkX = centerChunkX + dx
          val chunkZ = centerChunkZ + dz
          val cached = MinecraftPathingRules.isChunkCached(level, chunkX, chunkZ)
          val color =
            when {
              dx == 0 && dz == 0 -> ThemeManager.currentTheme.accentSecondary
              cached -> ThemeManager.currentTheme.accent
              else -> ThemeManager.currentTheme.overlay
            }
          val x = originX + (dx + radius) * cell
          val y = originY + (dz + radius) * cell
          NVGRenderer.rect(x, y, cell - 1f, cell - 1f, color)
        }
      }

      if (cacheHudShowGrid.value) {
        NVGRenderer.hollowRect(
          originX - 0.5f,
          originY - 0.5f,
          mapW + 1f,
          mapH + 1f,
          1f,
          ThemeManager.currentTheme.moduleDivider,
          4f
        )
      }
    }
  }

  init {
    addSetting(
      enabled,
      info,
      targetX,
      targetY,
      targetZ,
      targetBlock,
      cacheHudEnabled,
      cacheHudRadius,
      cacheHudCellSize,
      cacheHudShowGrid,
      startAction,
      stopAction,
    )

    EventBus.register(this)
  }

  @SubscribeEvent
  fun onRightClick(event: MouseEvent.RightClick) {
    // No-op: target is set via commands.
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      if (DuskPathfinder.isActive()) {
        DuskPathfinder.stop(mc, "Pathfinding disabled.")
      }
      return
    }
    DuskPathfinder.tick(mc)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value) return
    DuskPathfinder.renderTick()
    DuskPathfinder.render(event.context)
  }

  fun startFromSettings() {
    if (!enabled.value) {
      ChatUtils.sendMessage("Pathfinding is disabled. Enable it in the GUI first.")
      return
    }

    val x = parseCoordinate(targetX.value) ?: return invalidTarget("X", targetX.value)
    val y = parseCoordinate(targetY.value) ?: return invalidTarget("Y", targetY.value)
    val z = parseCoordinate(targetZ.value) ?: return invalidTarget("Z", targetZ.value)

    DuskPathfinder.start(mc, BlockPos.containing(x, y, z))
  }

  fun setTargetOnly(x: Double, y: Double, z: Double) {
    setTarget(x, y, z, null)
    ChatUtils.sendMessage("Target set to $x, $y, $z.")
  }

  fun startTo(x: Double, y: Double, z: Double) {
    setTarget(x, y, z, null)
    if (!enabled.value) {
      ChatUtils.sendMessage("Target updated. Enable Pathfinding in the GUI to start.")
      return
    }
    DuskPathfinder.start(mc, BlockPos.containing(x, y, z))
  }

  fun stopPath() {
    DuskPathfinder.stop(mc, "Stopped.")
  }

  fun setTargetAtPlayer() {
    val player = mc.player ?: return
    setTarget(player.x, player.y, player.z, "player")
    ChatUtils.sendMessage("Target set to your position.")
  }

  private fun setTarget(x: Double, y: Double, z: Double, blockName: String?) {
    targetX.value = formatCoord(x)
    targetY.value = formatCoord(y)
    targetZ.value = formatCoord(z)
    if (blockName != null) {
      targetBlock.value = blockName
    }
  }

  private fun formatCoord(value: Double): String {
    val intVal = value.toInt()
    return if (value == intVal.toDouble()) {
      intVal.toString()
    } else {
      String.format(Locale.US, "%.3f", value)
    }
  }

  private fun parseCoordinate(value: String): Double? {
    return value.trim().toDoubleOrNull()
  }

  private fun invalidTarget(axis: String, value: String) {
    ChatUtils.sendMessage("Invalid $axis coordinate: \"$value\"")
  }
}
