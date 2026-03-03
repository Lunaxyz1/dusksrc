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
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.AABB
import net.minecraft.core.Direction
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
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.BezierTrackingRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.DuskPathfinder
import org.cobalt.internal.pathfinding.HeadRotationModule
import org.cobalt.internal.pathfinding.PathfindingModule

object RoutesModule : Module("Routes") {

  enum class RoutePointType(val id: String) {
    NORMAL("normal"),
    WARP("warp"),
    MINE("mine");

    companion object {
      fun fromId(id: String?): RoutePointType {
        return when (id?.lowercase()) {
          "warp" -> WARP
          "mine" -> MINE
          else -> NORMAL
        }
      }
    }
  }

  data class RoutePoint(val pos: BlockPos, val type: RoutePointType, val mineEnd: BlockPos? = null)

  private enum class RouteAction {
    NONE,
    WALK,
    WARP,
    MINE
  }

  private val mc: Minecraft = Minecraft.getInstance()
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val routesFile = File(mc.gameDirectory, "config/cobalt/routes.json")

  private val routePoints = mutableListOf<RoutePoint>()
  private var routeIndex = 0
  private var routeRunning = false
  private var awaitingArrival = false
  private var lastTarget: BlockPos? = null
  private var lastResolvedTarget: BlockPos? = null
  private var action = RouteAction.NONE
  private var activePoint: RoutePoint? = null
  private var pendingClickPos: BlockPos? = null
  private var pendingMineStart: BlockPos? = null
  private var awaitingMineSecond = false

  private val rotationStrategy = BezierTrackingRotationStrategy(14f, 10f)

  private var warpStage = 0
  private var warpTarget: BlockPos? = null
  private var warpStageTicks = 0
  private var warpCooldownUntil = 0L
  private var warpRestoreSlot = -1

  private var mineBlocks: MutableSet<BlockPos> = LinkedHashSet()
  private var mineBlockId: String? = null
  private var mineTarget: BlockPos? = null
  private var minePathTarget: BlockPos? = null
  private var miningActive = false

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

  private val pointType = ModeSetting(
    "Point Type",
    "Type used when adding points.",
    0,
    arrayOf("Normal", "Warp", "Mine")
  )

  private val veinOccupancyRadius = SliderSetting(
    "Vein Occupancy Radius",
    "Radius around a vein considered occupied by other players.",
    6.0,
    1.0,
    16.0
  )

  private val openPickerAction = ActionSetting(
    "Point Picker",
    "Open a picker to choose the next point type.",
    "Open"
  ) {
    org.cobalt.internal.ui.hud.RoutePointPopup.open()
  }

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
    resetRuntimeState()
    DuskPathfinder.stop(mc, "Route cleared.")
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

  private val startClosestVeinAction = ActionSetting(
    "Path Closest Vein",
    "Pathfind to the nearest unoccupied mine point.",
    "Start"
  ) {
    pathToClosestVein()
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
      pointType,
      veinOccupancyRadius,
      openPickerAction,
      addPointAction,
      addCoordPointAction,
      removePointAction,
      clearRouteAction,
      saveRouteAction,
      loadRouteAction,
      startRouteAction,
      startClosestVeinAction,
      stopRouteAction,
    )

    EventBus.register(this)
    EventBus.register(org.cobalt.internal.ui.hud.RoutePointPopup)
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
    val level = mc.level ?: return
    if (routePoints.isEmpty()) {
      stopRoute("Route has no points.")
      return
    }

    if (mc.screen != null) {
      mc.options.keyUse?.setDown(false)
      mc.options.keyShift?.setDown(false)
      stopMiningKeys()
      RotationExecutor.stopRotating()
      return
    }

    if (warpStage > 0) {
      handleWarp(player, level)
      return
    }

    if (action == RouteAction.MINE) {
      handleMine(player, level)
      return
    }

    if (action == RouteAction.WALK) {
      if (DuskPathfinder.isActive()) {
        return
      }
      if (awaitingArrival) {
        val target = lastTarget
        if (target != null && hasArrived(player, target)) {
          completePoint()
        } else {
          stopRoute("Route failed: pathfinder stopped early.")
        }
      }
    }

    if (action == RouteAction.NONE) {
      startNextPoint(player, level)
    }
  }

  @SubscribeEvent
  fun onRightClick(@Suppress("UNUSED_PARAMETER") event: MouseEvent.RightClick) {
    if (!enabled.value || !recordOnRightClick.value) return
    val hit = mc.hitResult
    if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
      val clicked = hit.blockPos
      if (awaitingMineSecond) {
        val start = pendingMineStart
        if (start != null) {
          routePoints.add(RoutePoint(start, RoutePointType.MINE, clicked))
          ChatUtils.sendMessage("Mine point added (start -> end).")
          updateStatus()
        }
        pendingMineStart = null
        awaitingMineSecond = false
        return
      }
      pendingClickPos = clicked
      org.cobalt.internal.ui.hud.RoutePointPopup.open()
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value || !renderRoute.value) return
    if (routePoints.isEmpty()) return
    val level = mc.level ?: return
    val segments = max(1, routePoints.size - 1)
    if (routePoints.size >= 2) {
      for (i in 0 until routePoints.size - 1) {
        val a = routePoints[i].pos
        val b = routePoints[i + 1].pos
        val color = gradientColor(i / (segments - 1.0))
        val start = Vec3(a.x + 0.5, a.y + 0.2, a.z + 0.5)
        val end = Vec3(b.x + 0.5, b.y + 0.2, b.z + 0.5)
        Render3D.drawLine(event.context, start, end, color, true, 2.0f)
      }
    }
    for (i in routePoints.indices) {
      val point = routePoints[i]
      val p = point.pos
      val color = pointTypeColor(point.type, i / (segments - 1.0))
      if (point.type == RoutePointType.MINE) {
        val end = point.mineEnd
        if (end != null) {
          val startVec = Vec3(p.x + 0.5, p.y + 0.4, p.z + 0.5)
          val endVec = Vec3(end.x + 0.5, end.y + 0.4, end.z + 0.5)
          Render3D.drawLine(event.context, startVec, endVec, color, true, 2.5f)
          val endBox = AABB(
            end.x.toDouble(),
            end.y.toDouble(),
            end.z.toDouble(),
            end.x + 1.0,
            end.y + 1.0,
            end.z + 1.0
          )
          Render3D.drawBox(event.context, endBox, color, true)
          highlightVein(level, p, end, color, event.context)
        }
      }
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
    routePoints.add(RoutePoint(player.blockPosition(), currentPointType()))
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
    routePoints.add(RoutePoint(BlockPos(x, y, z), currentPointType()))
    updateStatus()
  }

  private fun startRoute() {
    if (routePoints.isEmpty()) {
      ChatUtils.sendMessage("Route has no points.")
      return
    }
    PathfindingModule.ensureEnabledForAutomation("routes")
    routeIndex = 0
    routeRunning = true
    resetRuntimeState()
    updateStatus()
  }

  private fun pathToClosestVein() {
    val player = mc.player ?: return
    val level = mc.level ?: return
    if (routePoints.isEmpty()) {
      ChatUtils.sendMessage("Route has no points.")
      return
    }

    if (routeRunning) {
      stopRoute("Switching to closest vein.")
    }

    val candidates = routePoints.filter { it.type == RoutePointType.MINE && it.mineEnd != null }
    if (candidates.isEmpty()) {
      ChatUtils.sendMessage("No mine points available.")
      return
    }

    var bestPoint: RoutePoint? = null
    var bestTarget: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    val origin = player.blockPosition()

    for (point in candidates) {
      if (isVeinOccupied(level, point, player)) continue
      val target = resolveApproxTarget(point.pos) ?: continue
      val distSq = target.distSqr(origin).toDouble()
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        bestPoint = point
        bestTarget = target
      }
    }

    if (bestPoint == null || bestTarget == null) {
      ChatUtils.sendMessage("No unoccupied mine points found.")
      return
    }

    PathfindingModule.ensureEnabledForAutomation("routes")

    val started = DuskPathfinder.start(mc, bestTarget)
    if (!started) {
      ChatUtils.sendMessage("Failed to path to closest vein.")
      return
    }

    ChatUtils.sendMessage(
      "Pathing to vein at ${bestPoint.pos.x} ${bestPoint.pos.y} ${bestPoint.pos.z}."
    )
  }

  private fun stopRoute(reason: String) {
    routeRunning = false
    resetRuntimeState()
    DuskPathfinder.stop(mc, reason)
    updateStatus()
  }

  private fun updateStatus() {
    pointsText.value = routePoints.size.toString()
    statusText.value = if (!routeRunning) {
      "Idle"
    } else {
      if (routePoints.isEmpty()) {
        "Running 0/0"
      } else {
        val current = (routeIndex + 1).coerceAtMost(routePoints.size)
        "Running $current/${routePoints.size}"
      }
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
    routePoints.forEach { point ->
      val obj = JsonObject()
      obj.addProperty("x", point.pos.x)
      obj.addProperty("y", point.pos.y)
      obj.addProperty("z", point.pos.z)
      obj.addProperty("type", point.type.id)
      point.mineEnd?.let { end ->
        obj.addProperty("mx", end.x)
        obj.addProperty("my", end.y)
        obj.addProperty("mz", end.z)
      }
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
    val loaded = mutableListOf<RoutePoint>()
    pointsArray.forEach { el ->
      val obj = el.asJsonObject
      val x = obj.get("x")?.asInt ?: return@forEach
      val y = obj.get("y")?.asInt ?: return@forEach
      val z = obj.get("z")?.asInt ?: return@forEach
      val type = RoutePointType.fromId(obj.get("type")?.asString)
      val mx = obj.get("mx")?.asInt
      val my = obj.get("my")?.asInt
      val mz = obj.get("mz")?.asInt
      val mineEnd = if (mx != null && my != null && mz != null) BlockPos(mx, my, mz) else null
      loaded.add(RoutePoint(BlockPos(x, y, z), type, mineEnd))
    }
    routePoints.clear()
    routePoints.addAll(loaded)
    routeIndex = 0
    routeRunning = false
    resetRuntimeState()
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

  private fun pointTypeColor(type: RoutePointType, t: Double): Color {
    return when (type) {
      RoutePointType.WARP -> Color(175, 120, 255, 255)
      RoutePointType.MINE -> Color(80, 255, 140, 255)
      RoutePointType.NORMAL -> gradientColor(t)
    }
  }

  private fun highlightVein(
    level: net.minecraft.world.level.Level,
    start: BlockPos,
    end: BlockPos,
    color: Color,
    context: org.cobalt.api.event.impl.render.WorldRenderContext
  ) {
    val state = level.getBlockState(start)
    if (state.isAir) return
    val block = state.block

    val minX = minOf(start.x, end.x)
    val maxX = maxOf(start.x, end.x)
    val minY = minOf(start.y, end.y)
    val maxY = maxOf(start.y, end.y)
    val minZ = minOf(start.z, end.z)
    val maxZ = maxOf(start.z, end.z)

    val queue = ArrayDeque<BlockPos>()
    val visited = HashSet<Long>()
    queue.add(start)
    visited.add(start.asLong())

    var rendered = 0
    val maxBlocks = 256

    while (queue.isNotEmpty() && rendered < maxBlocks) {
      val pos = queue.removeFirst()
      if (pos.x !in minX..maxX || pos.y !in minY..maxY || pos.z !in minZ..maxZ) {
        continue
      }
      val curState = level.getBlockState(pos)
      if (curState.isAir || curState.block != block) {
        continue
      }

      if (isExposed(level, pos)) {
        val box = AABB(
          pos.x.toDouble(),
          pos.y.toDouble(),
          pos.z.toDouble(),
          pos.x + 1.0,
          pos.y + 1.0,
          pos.z + 1.0
        )
        Render3D.drawBox(context, box, color, true)
        rendered++
      }

      for (dir in Direction.values()) {
        val next = pos.relative(dir)
        val key = next.asLong()
        if (visited.add(key)) {
          queue.add(next)
        }
      }
    }
  }

  private fun isExposed(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
    for (dir in Direction.values()) {
      val adj = pos.relative(dir)
      if (MinecraftPathingRules.isPassable(level, adj)) {
        return true
      }
    }
    return false
  }

  private fun currentPointType(): RoutePointType {
    return when (pointType.value) {
      1 -> RoutePointType.WARP
      2 -> RoutePointType.MINE
      else -> RoutePointType.NORMAL
    }
  }

  fun setPointType(type: RoutePointType) {
    pointType.value = when (type) {
      RoutePointType.WARP -> 1
      RoutePointType.MINE -> 2
      else -> 0
    }
  }

  fun applyPickedType(type: RoutePointType) {
    setPointType(type)
    val clicked = pendingClickPos
    if (clicked == null) {
      return
    }
    if (type == RoutePointType.MINE) {
      pendingMineStart = clicked
      awaitingMineSecond = true
      pendingClickPos = null
      ChatUtils.sendMessage("Mine point: select the end block.")
      return
    }
    routePoints.add(RoutePoint(clicked, type))
    pendingClickPos = null
    updateStatus()
  }

  fun cancelPendingPick() {
    pendingClickPos = null
    pendingMineStart = null
    awaitingMineSecond = false
  }

  private fun isVeinOccupied(
    level: net.minecraft.world.level.Level,
    point: RoutePoint,
    player: net.minecraft.world.entity.player.Player
  ): Boolean {
    val end = point.mineEnd ?: point.pos
    val radius = veinOccupancyRadius.value
    val minX = minOf(point.pos.x, end.x).toDouble() - radius
    val minY = minOf(point.pos.y, end.y).toDouble() - radius
    val minZ = minOf(point.pos.z, end.z).toDouble() - radius
    val maxX = maxOf(point.pos.x, end.x).toDouble() + 1.0 + radius
    val maxY = maxOf(point.pos.y, end.y).toDouble() + 1.0 + radius
    val maxZ = maxOf(point.pos.z, end.z).toDouble() + 1.0 + radius
    val aabb = AABB(minX, minY, minZ, maxX, maxY, maxZ)

    for (other in level.players()) {
      if (other == player) continue
      if (other.isSpectator) continue
      if (aabb.intersects(other.boundingBox)) {
        return true
      }
    }
    return false
  }

  private fun startNextPoint(player: Player, level: net.minecraft.world.level.Level) {
    if (routeIndex >= routePoints.size) {
      stopRoute("Route complete.")
      return
    }

    val point = routePoints[routeIndex]
    activePoint = point

    when (point.type) {
      RoutePointType.WARP -> {
        if (hasArrived(player, point.pos)) {
          completePoint()
          return
        }
        if (startWarp(point.pos)) {
          action = RouteAction.WARP
          return
        }
        if (
          level.gameTime < warpCooldownUntil &&
          (EtherwarpLogic.holdingEtherwarpItem() || EtherwarpLogic.findEtherwarpHotbarSlot() in 0..8)
        ) {
          return
        }
        startWalk(point.pos)
      }
      RoutePointType.MINE -> {
        startMine(level, point)
      }
      else -> {
        startWalk(point.pos)
      }
    }
  }

  private fun startWalk(target: BlockPos) {
    PathfindingModule.ensureEnabledForAutomation("routes")
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
    action = RouteAction.WALK
    awaitingArrival = true
    lastTarget = target
    lastResolvedTarget = resolved
  }

  private fun startMine(level: net.minecraft.world.level.Level, point: RoutePoint) {
    val end = point.mineEnd ?: run {
      startWalk(point.pos)
      return
    }

    val vein = buildMineVein(level, point.pos, end)
    if (vein == null || vein.blocks.isEmpty()) {
      ChatUtils.sendMessage("Mine point empty; skipping.")
      completePoint()
      return
    }

    mineBlocks = vein.blocks
    mineBlockId = vein.blockId
    mineTarget = null
    minePathTarget = null
    action = RouteAction.MINE
  }

  private fun handleMine(player: Player, level: net.minecraft.world.level.Level) {
    pruneMineBlocks(level)
    if (mineBlocks.isEmpty()) {
      finishMine("Vein complete.")
      return
    }

    val target = selectMineTarget(level, player, mineBlocks)
    if (target != null) {
      mineTarget = target
      if (DuskPathfinder.isActive()) {
        DuskPathfinder.stop(mc, "Mining.")
      }
      startMining(target)
      return
    }

    stopMiningKeys()
    RotationExecutor.stopRotating()

    val nearest = selectNearestBlock(player, mineBlocks)
    if (nearest != null) {
      moveToward(level, player, nearest)
    } else {
      finishMine("No mine targets.")
    }
  }

  private fun finishMine(reason: String) {
    ChatUtils.sendMessage(reason)
    stopMiningKeys()
    RotationExecutor.stopRotating()
    resetMineState()
    completePoint()
  }

  private fun completePoint() {
    action = RouteAction.NONE
    awaitingArrival = false
    lastTarget = null
    lastResolvedTarget = null
    activePoint = null
    routeIndex++
    stopMiningKeys()
    RotationExecutor.stopRotating()
    restoreEtherwarpSlot()
    resetWarp()
    resetMineState()
    if (routeIndex >= routePoints.size) {
      stopRoute("Route complete.")
      return
    }
    updateStatus()
  }

  private fun resetRuntimeState() {
    action = RouteAction.NONE
    awaitingArrival = false
    lastTarget = null
    lastResolvedTarget = null
    activePoint = null
    stopMiningKeys()
    RotationExecutor.stopRotating()
    mc.options.keyUse?.setDown(false)
    mc.options.keyShift?.setDown(false)
    restoreEtherwarpSlot()
    resetWarp()
    resetMineState()
  }

  private fun resetMineState() {
    mineBlocks.clear()
    mineBlockId = null
    mineTarget = null
    minePathTarget = null
    miningActive = false
  }

  private fun startMining(target: BlockPos) {
    val aim = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    RotationExecutor.rotateTo(AngleUtils.getRotation(aim), rotationStrategy)
    mc.options.keyAttack?.setDown(true)
    miningActive = true
  }

  private fun stopMiningKeys() {
    if (miningActive) {
      mc.options.keyAttack?.setDown(false)
      miningActive = false
    }
  }

  private fun selectMineTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    blocks: Set<BlockPos>
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    val rangeSq = MINE_RANGE * MINE_RANGE
    for (pos in blocks) {
      val distSq = distanceToBlockSq(player, pos)
      if (distSq > rangeSq) continue
      if (MINE_REQUIRE_LOS && !hasLineOfSight(level, player, pos)) continue
      if (distSq < bestDist) {
        bestDist = distSq
        best = pos
      }
    }
    return best
  }

  private fun selectNearestBlock(player: Player, blocks: Set<BlockPos>): BlockPos? {
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (pos in blocks) {
      val distSq = distanceToBlockSq(player, pos)
      if (distSq < bestDist) {
        bestDist = distSq
        best = pos
      }
    }
    return best
  }

  private fun moveToward(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ) {
    val approach = findApproach(level, player, target) ?: return
    PathfindingModule.ensureEnabledForAutomation("routes")
    val distSq = minePathTarget?.distSqr(approach)?.toDouble() ?: Double.POSITIVE_INFINITY
    if (!DuskPathfinder.isActive() || distSq > 1.0) {
      DuskPathfinder.start(mc, approach)
      minePathTarget = approach
    }
  }

  private fun findApproach(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    val dirs = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
    for (dir in dirs) {
      val pos = target.relative(dir)
      if (!MinecraftPathingRules.isWalkable(level, pos)) continue
      val distSq = player.blockPosition().distSqr(pos).toDouble()
      if (distSq < bestDist) {
        bestDist = distSq
        best = pos
      }
    }
    if (best != null) return best
    return MinecraftPathingRules.resolveTarget(level, target)
  }

  private fun pruneMineBlocks(level: net.minecraft.world.level.Level) {
    val id = mineBlockId ?: return
    val iterator = mineBlocks.iterator()
    while (iterator.hasNext()) {
      val pos = iterator.next()
      if (blockIdAt(level, pos) != id) {
        iterator.remove()
      }
    }
  }

  private fun buildMineVein(
    level: net.minecraft.world.level.Level,
    start: BlockPos,
    end: BlockPos
  ): MineVein? {
    val minX = minOf(start.x, end.x)
    val maxX = maxOf(start.x, end.x)
    val minY = minOf(start.y, end.y)
    val maxY = maxOf(start.y, end.y)
    val minZ = minOf(start.z, end.z)
    val maxZ = maxOf(start.z, end.z)

    val seed = findMineSeed(level, start, minX, maxX, minY, maxY, minZ, maxZ) ?: return null
    val blockId = blockIdAt(level, seed)

    val blocks = LinkedHashSet<BlockPos>()
    val queue = ArrayDeque<BlockPos>()
    queue.add(seed)
    blocks.add(seed)

    while (queue.isNotEmpty() && blocks.size < MINE_MAX_BLOCKS) {
      val pos = queue.removeFirst()
      for (dir in Direction.values()) {
        val next = pos.relative(dir)
        if (next.x !in minX..maxX || next.y !in minY..maxY || next.z !in minZ..maxZ) continue
        if (blocks.contains(next)) continue
        if (blockIdAt(level, next) != blockId) continue
        blocks.add(next)
        queue.add(next)
        if (blocks.size >= MINE_MAX_BLOCKS) break
      }
    }

    return MineVein(blockId, blocks)
  }

  private fun findMineSeed(
    level: net.minecraft.world.level.Level,
    start: BlockPos,
    minX: Int,
    maxX: Int,
    minY: Int,
    maxY: Int,
    minZ: Int,
    maxZ: Int
  ): BlockPos? {
    if (!level.getBlockState(start).isAir) {
      return start
    }
    for (y in minY..maxY) {
      for (x in minX..maxX) {
        for (z in minZ..maxZ) {
          val pos = BlockPos(x, y, z)
          if (!level.getBlockState(pos).isAir) {
            return pos
          }
        }
      }
    }
    return null
  }

  private fun hasLineOfSight(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Boolean {
    val eye = player.eyePosition
    val center = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val hit = level.clip(
      net.minecraft.world.level.ClipContext(
        eye,
        center,
        net.minecraft.world.level.ClipContext.Block.OUTLINE,
        net.minecraft.world.level.ClipContext.Fluid.NONE,
        player
      )
    )
    return hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK &&
      hit.blockPos == target
  }

  private fun blockIdAt(level: net.minecraft.world.level.Level, pos: BlockPos): String {
    val state = level.getBlockState(pos)
    return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
  }

  private fun distanceToBlockSq(player: Player, pos: BlockPos): Double {
    val dx = (pos.x + 0.5) - player.x
    val dy = (pos.y + 0.5) - player.y
    val dz = (pos.z + 0.5) - player.z
    return dx * dx + dy * dy + dz * dz
  }

  private fun hasArrived(player: Player, target: BlockPos): Boolean {
    val distSq = player.blockPosition().distSqr(target).toDouble()
    return distSq <= ARRIVAL_DISTANCE_SQ
  }

  private fun applyWarpHeadRotation(player: Player, target: Vec3): Pair<Double, Double> {
    val targetRotation = AngleUtils.getRotation(target)
    val yawDelta = AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)
    val yawStep = HeadRotationModule.computeTurnDelta(yawDelta, maxSpeedScale = 1.35f, accelScale = 1.20f)
    player.yRot = AngleUtils.normalizeAngle(player.yRot + yawStep)
    player.yHeadRot = player.yRot
    player.yBodyRot = player.yRot

    val pitchDelta = (targetRotation.pitch - player.xRot).coerceIn(-6f, 6f)
    player.xRot = (player.xRot + pitchDelta).coerceIn(-89.9f, 89.9f)

    val yawError = kotlin.math.abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)).toDouble()
    val pitchError = kotlin.math.abs(targetRotation.pitch - player.xRot).toDouble()
    return yawError to pitchError
  }

  private fun startWarp(target: BlockPos): Boolean {
    val player = mc.player ?: return false
    val level = mc.level ?: return false
    if (level.gameTime < warpCooldownUntil) return false
    if (!ensureEtherwarpHotbarSelected()) return false
    if (!EtherwarpLogic.holdingEtherwarpItem()) return false

    val eye = player.eyePosition
    val targetCenter = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val distSq = eye.distanceToSqr(targetCenter)
    val range = EtherwarpLogic.getEtherwarpRange().toDouble()
    if (distSq > range * range) return false
    if (!hasLineOfSight(level, player, target)) return false
    if (!ensureEtherwarpHotbarSelected()) return false

    if (DuskPathfinder.isActive()) {
      DuskPathfinder.stop(mc, "Warping.")
    }
    RotationExecutor.stopRotating()
    mc.options.keyUse?.setDown(false)
    mc.options.keyShift?.setDown(false)

    warpTarget = target
    warpStage = 0
    warpStageTicks = 0
    action = RouteAction.WARP
    return true
  }

  private fun handleWarp(
    player: Player,
    level: net.minecraft.world.level.Level
  ) {
    val target = warpTarget ?: run {
      resetWarp()
      return
    }

    val targetCenter = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val (yawError, pitchError) = applyWarpHeadRotation(player, targetCenter)

    when (warpStage) {
      0 -> {
        if (
          (yawError <= WARP_AIM_TOLERANCE && pitchError <= WARP_AIM_TOLERANCE) ||
          warpStageTicks >= WARP_ALIGN_TICKS
        ) {
          mc.options.keyShift?.setDown(true)
          warpStage = 1
          warpStageTicks = 0
          return
        }
        warpStageTicks++
      }
      1 -> {
        mc.options.keyShift?.setDown(true)
        if (warpStageTicks >= WARP_SNEAK_TICKS) {
          MouseUtils.rightClick()
          player.swing(InteractionHand.MAIN_HAND)
          warpStage = 2
          warpStageTicks = 0
          return
        }
        warpStageTicks++
      }
      else -> {
        if (warpStageTicks >= WARP_POST_TICKS) {
          mc.options.keyUse?.setDown(false)
          mc.options.keyShift?.setDown(false)
          warpCooldownUntil = level.gameTime + WARP_COOLDOWN_TICKS
          restoreEtherwarpSlot()
          resetWarp()
          if (hasArrived(player, target)) {
            completePoint()
          } else if (!startWarp(target)) {
            startWalk(target)
          }
          return
        }
        warpStageTicks++
      }
    }
  }

  private fun resetWarp() {
    mc.options.keyUse?.setDown(false)
    mc.options.keyShift?.setDown(false)
    warpStage = 0
    warpTarget = null
    warpStageTicks = 0
  }

  private fun ensureEtherwarpHotbarSelected(): Boolean {
    val player = mc.player ?: return false
    val currentSlot = player.inventory.selectedSlot
    val currentStack = player.inventory.getItem(currentSlot)
    if (EtherwarpLogic.isEtherwarpStack(currentStack)) {
      return true
    }
    if (EtherwarpLogic.isEtherwarpStack(player.offhandItem)) {
      return true
    }
    val slot = EtherwarpLogic.findEtherwarpHotbarSlot()
    if (slot in 0..8) {
      if (warpRestoreSlot == -1) {
        warpRestoreSlot = currentSlot
      }
      InventoryUtils.holdHotbarSlot(slot)
      return true
    }
    return false
  }

  private fun restoreEtherwarpSlot() {
    if (warpRestoreSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(warpRestoreSlot)
    }
    warpRestoreSlot = -1
  }

  private const val ARRIVAL_DISTANCE_SQ = 6.0 * 6.0
  private const val APPROX_SCAN_RADIUS = 6
  private const val APPROX_SCAN_VERTICAL = 4
  private const val MINE_RANGE = 4.5
  private const val MINE_REQUIRE_LOS = true
  private const val MINE_MAX_BLOCKS = 256
  private const val WARP_AIM_TOLERANCE = 6.0
  private const val WARP_ALIGN_TICKS = 20
  private const val WARP_SNEAK_TICKS = 2
  private const val WARP_POST_TICKS = 6
  private const val WARP_COOLDOWN_TICKS = 24L

  private data class MineVein(
    val blockId: String,
    val blocks: MutableSet<BlockPos>
  )
}
