package org.cobalt.internal.mining

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Color
import java.io.File
import kotlin.math.max
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.AABB
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.internal.pathfinding.DuskPathfinder

object RoutesModule : Module("Routes") {

  private val mc: Minecraft = Minecraft.getInstance()
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val routesFile = File(mc.gameDirectory, "config/cobalt/routes.json")

  private val routePoints = mutableListOf<BlockPos>()
  private var routeIndex = 0
  private var routeRunning = false
  private var awaitingArrival = false
  private var lastTarget: BlockPos? = null
  private var lastResolvedTarget: BlockPos? = null

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable route tools.",
    false
  )

  private val info = InfoSetting(
    "Route Builder",
    "Add points, save/load, and run routes with the pathfinder.",
    InfoType.INFO
  )

  val routeName = TextSetting(
    "Route Name",
    "Name used for save/load.",
    "default"
  )

  val coordX = TextSetting(
    "Add X",
    "X coordinate to add as a route point.",
    ""
  )
  val coordY = TextSetting(
    "Add Y",
    "Y coordinate to add as a route point.",
    ""
  )
  val coordZ = TextSetting(
    "Add Z",
    "Z coordinate to add as a route point.",
    ""
  )

  val pointsText = TextSetting(
    "Points",
    "Number of points in the current route.",
    "0"
  )

  val statusText = TextSetting(
    "Status",
    "Current route status.",
    "Idle"
  )

  val renderRoute = CheckboxSetting(
    "Render Route",
    "Render the route in-world.",
    true
  )

  val recordOnRightClick = CheckboxSetting(
    "Record on Right Click",
    "Add a route point when you right-click a block.",
    false
  )

  private val addPointAction = ActionSetting(
    "Add Point",
    "Add a point at your position.",
    "Add"
  ) {
    addPointFromPlayer()
  }

  private val addCoordPointAction = ActionSetting(
    "Add Coord Point",
    "Add a point using the Add X/Y/Z fields.",
    "Add"
  ) {
    addPointFromCoords()
  }

  private val removePointAction = ActionSetting(
    "Remove Last",
    "Remove the last route point.",
    "Remove"
  ) {
    if (routePoints.isNotEmpty()) {
      routePoints.removeAt(routePoints.lastIndex)
      updateStatus()
    }
  }

  private val clearRouteAction = ActionSetting(
    "Clear Route",
    "Remove all route points.",
    "Clear"
  ) {
    routePoints.clear()
    routeIndex = 0
    routeRunning = false
    awaitingArrival = false
    lastTarget = null
    updateStatus()
  }

  private val saveRouteAction = ActionSetting(
    "Save Route",
    "Save route points under the current name.",
    "Save"
  ) {
    saveRoute()
  }

  private val loadRouteAction = ActionSetting(
    "Load Route",
    "Load route points by name.",
    "Load"
  ) {
    loadRoute()
  }

  private val startRouteAction = ActionSetting(
    "Start Route",
    "Run the current route with the pathfinder.",
    "Start"
  ) {
    startRoute()
  }

  private val stopRouteAction = ActionSetting(
    "Stop Route",
    "Stop running the route.",
    "Stop"
  ) {
    stopRoute("Stopped.")
  }

  init {
    addSetting(
      enabled,
      info,
      routeName,
      coordX,
      coordY,
      coordZ,
      pointsText,
      statusText,
      renderRoute,
      recordOnRightClick,
      addPointAction,
      addCoordPointAction,
      removePointAction,
      clearRouteAction,
      saveRouteAction,
      loadRouteAction,
      startRouteAction,
      stopRouteAction,
    )

    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      if (routeRunning) {
        stopRoute("Routes disabled.")
      }
      return
    }

    updateStatus()

    if (!routeRunning) return

    val player = mc.player ?: return
    if (routePoints.isEmpty()) {
      stopRoute("Route has no points.")
      return
    }

    if (!DuskPathfinder.isActive()) {
      if (awaitingArrival) {
        val target = lastTarget
        if (target != null) {
          val distSq = player.blockPosition().distSqr(target).toDouble()
          if (distSq <= ARRIVAL_DISTANCE_SQ) {
            awaitingArrival = false
          } else {
            stopRoute("Route failed: pathfinder stopped early.")
            return
          }
        } else {
          awaitingArrival = false
        }
      }

      if (!awaitingArrival) {
        if (routeIndex >= routePoints.size) {
          stopRoute("Route complete.")
          return
        }
        val target = routePoints[routeIndex]
        val resolved = resolveApproxTarget(target)
        if (resolved == null) {
          stopRoute("Route failed: no walkable target near point ${routeIndex + 1}.")
          return
        }
        val started = DuskPathfinder.start(mc, resolved)
        if (!started) {
          stopRoute("Route failed: no path to point ${routeIndex + 1}.")
          return
        }
        lastTarget = target
        lastResolvedTarget = resolved
        awaitingArrival = true
        routeIndex++
      }
    }
  }

  @SubscribeEvent
  fun onRightClick(@Suppress("UNUSED_PARAMETER") event: MouseEvent.RightClick) {
    if (!enabled.value || !recordOnRightClick.value) return
    val hit = mc.hitResult
    if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
      routePoints.add(hit.blockPos)
      updateStatus()
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value || !renderRoute.value) return
    if (routePoints.size < 2) return
    val segments = max(1, routePoints.size - 1)
    for (i in 0 until routePoints.size - 1) {
      val a = routePoints[i]
      val b = routePoints[i + 1]
      val color = gradientColor(i / (segments - 1.0))
      val start = Vec3(a.x + 0.5, a.y + 0.2, a.z + 0.5)
      val end = Vec3(b.x + 0.5, b.y + 0.2, b.z + 0.5)
      Render3D.drawLine(event.context, start, end, color, true, 2.0f)
    }
    for (i in routePoints.indices) {
      val p = routePoints[i]
      val color = gradientColor(i / (segments - 1.0))
      val box = AABB(
        p.x.toDouble(),
        p.y.toDouble(),
        p.z.toDouble(),
        p.x + 1.0,
        p.y + 1.0,
        p.z + 1.0
      )
      Render3D.drawBox(event.context, box, color, true)
    }
  }

  private fun addPointFromPlayer() {
    val player = mc.player ?: return
    routePoints.add(player.blockPosition())
    updateStatus()
  }

  private fun addPointFromCoords() {
    val x = coordX.value.trim().toIntOrNull()
    val y = coordY.value.trim().toIntOrNull()
    val z = coordZ.value.trim().toIntOrNull()
    if (x == null || y == null || z == null) {
      ChatUtils.sendMessage("Invalid coordinates. Use integers for Add X/Y/Z.")
      return
    }
    routePoints.add(BlockPos(x, y, z))
    updateStatus()
  }

  private fun startRoute() {
    if (routePoints.isEmpty()) {
      ChatUtils.sendMessage("Route has no points.")
      return
    }
    routeIndex = 0
    routeRunning = true
    awaitingArrival = false
    lastTarget = null
    lastResolvedTarget = null
    updateStatus()
  }

  private fun stopRoute(reason: String) {
    routeRunning = false
    awaitingArrival = false
    lastTarget = null
    lastResolvedTarget = null
    DuskPathfinder.stop(mc, reason)
    updateStatus()
  }

  private fun updateStatus() {
    pointsText.value = routePoints.size.toString()
    statusText.value = if (!routeRunning) {
      "Idle"
    } else {
      "Running ${routeIndex.coerceAtMost(routePoints.size)}/${routePoints.size}"
    }
  }

  private fun saveRoute() {
    val name = routeName.value.trim()
    if (name.isEmpty()) {
      ChatUtils.sendMessage("Route name is empty.")
      return
    }
    if (!routesFile.parentFile.exists()) {
      routesFile.parentFile.mkdirs()
    }
    val root = readRoutesJson()
    val routesObj = root.getAsJsonObject("routes") ?: JsonObject()
    val pointsArray = JsonArray()
    routePoints.forEach { pos ->
      val obj = JsonObject()
      obj.addProperty("x", pos.x)
      obj.addProperty("y", pos.y)
      obj.addProperty("z", pos.z)
      pointsArray.add(obj)
    }
    routesObj.add(name, pointsArray)
    root.add("routes", routesObj)
    routesFile.writeText(gson.toJson(root))
    ChatUtils.sendMessage("Saved route \"$name\" (${routePoints.size} points).")
  }

  private fun loadRoute() {
    val name = routeName.value.trim()
    if (name.isEmpty()) {
      ChatUtils.sendMessage("Route name is empty.")
      return
    }
    val root = readRoutesJson()
    val routesObj = root.getAsJsonObject("routes") ?: run {
      ChatUtils.sendMessage("No routes saved yet.")
      return
    }
    val pointsArray = routesObj.getAsJsonArray(name) ?: run {
      ChatUtils.sendMessage("Route \"$name\" not found.")
      return
    }
    val loaded = mutableListOf<BlockPos>()
    pointsArray.forEach { el ->
      val obj = el.asJsonObject
      val x = obj.get("x")?.asInt ?: return@forEach
      val y = obj.get("y")?.asInt ?: return@forEach
      val z = obj.get("z")?.asInt ?: return@forEach
      loaded.add(BlockPos(x, y, z))
    }
    routePoints.clear()
    routePoints.addAll(loaded)
    routeIndex = 0
    routeRunning = false
    awaitingArrival = false
    lastTarget = null
    lastResolvedTarget = null
    updateStatus()
    ChatUtils.sendMessage("Loaded route \"$name\" (${routePoints.size} points).")
  }

  private fun readRoutesJson(): JsonObject {
    if (!routesFile.exists()) return JsonObject()
    val text = runCatching { routesFile.readText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return JsonObject()
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrDefault(JsonObject())
  }

  private fun resolveApproxTarget(target: BlockPos): BlockPos? {
    val level = mc.level ?: return null
    MinecraftPathingRules.resolveTarget(level, target)?.let { return it }
    return findNearestWalkable(level, target, APPROX_SCAN_RADIUS, APPROX_SCAN_VERTICAL)
  }

  private fun findNearestWalkable(
    level: net.minecraft.world.level.Level,
    origin: BlockPos,
    radius: Int,
    vertical: Int
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (dy in -vertical..vertical) {
      for (dx in -radius..radius) {
        for (dz in -radius..radius) {
          val pos = origin.offset(dx, dy, dz)
          if (!MinecraftPathingRules.isWalkable(level, pos)) continue
          val distSq = pos.distSqr(origin).toDouble()
          if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = pos
          }
        }
      }
    }
    return best
  }

  private fun gradientColor(t: Double): Color {
    val clamped = t.coerceIn(0.0, 1.0)
    val r = (0 + (255 - 0) * clamped).toInt()
    val g = (255 + (0 - 255) * clamped).toInt()
    val b = 255
    return Color(r, g, b, 255)
  }

  private const val ARRIVAL_DISTANCE_SQ = 6.0 * 6.0
  private const val APPROX_SCAN_RADIUS = 6
  private const val APPROX_SCAN_VERTICAL = 4
}
