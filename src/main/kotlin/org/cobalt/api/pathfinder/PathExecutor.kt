package org.cobalt.api.pathfinder

import java.awt.Color
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.pathfinder.pathfinder.AStarPathfinder
import org.cobalt.api.pathfinder.pathing.configuration.PathfinderConfiguration
import org.cobalt.api.pathfinder.pathing.heuristic.HeuristicWeights
import org.cobalt.api.pathfinder.pathing.heuristic.LinearHeuristicStrategy
import org.cobalt.api.pathfinder.pathing.minecraft.MinecraftParkourNeighborStrategy
import org.cobalt.api.pathfinder.pathing.processing.impl.AvoidanceProcessor
import org.cobalt.api.pathfinder.pathing.processing.impl.MinecraftParkourProcessor
import org.cobalt.api.pathfinder.pathing.processing.impl.MinecraftPathProcessor
import org.cobalt.api.pathfinder.pathing.result.Path
import org.cobalt.api.pathfinder.pathing.result.PathState
import org.cobalt.api.pathfinder.provider.impl.MinecraftNavigationProvider
import org.cobalt.api.pathfinder.result.PathImpl
import org.cobalt.api.pathfinder.wrapper.PathPosition
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.render.Render3D

/*
 * TODO: im lazy right now, but chunk and world caching would be alot better,
 *  if someone could help me do this it would be a great help :))
 */
object PathExecutor {

  private val mc: Minecraft = Minecraft.getInstance()
  private var currentPath: Path? = null
  private var currentWaypointIndex: Int = 0

  init {
    EventBus.register(this)
  }

  fun start(x: Double, y: Double, z: Double) {
    val player = mc.player ?: return
    val start = PathPosition(player.x, player.y, player.z)
    val target = PathPosition(x, y, z)

    val config =
      PathfinderConfiguration(
        provider = MinecraftNavigationProvider(),
        // as of now max iterations is 20,000 but maybe wanna higher
        maxIterations = 20000,
        async = true,
        neighborStrategy = MinecraftParkourNeighborStrategy,
        heuristicWeights = HeuristicWeights(1.0, 0.0, 0.0, 0.5),
        heuristicStrategy = LinearHeuristicStrategy(),
        processors = listOf(AvoidanceProcessor(), MinecraftParkourProcessor(), MinecraftPathProcessor())
      )

    val pathfinder = AStarPathfinder(config)

    ChatUtils.sendDebug("Calculating path to $x, $y, $z...")
    val startTime = System.currentTimeMillis()
    pathfinder.findPath(start, target).thenAccept { result ->
      mc.execute {
        val duration = System.currentTimeMillis() - startTime
        val state = result.getPathState()
        if (state != PathState.FOUND) {
          ChatUtils.sendMessage("§cFailed to find path: $state")
          return@execute
        }

        val rawPath = result.getPath()
        val positions = rawPath.collect().toList()
        val path =
          if (positions.size <= 2) {
            rawPath
          } else {
            val simplified = ArrayList<PathPosition>(positions.size)
            simplified.add(positions.first())

            for (i in 1 until positions.size - 1) {
              val prev = simplified.last()
              val curr = positions[i]
              val next = positions[i + 1]

              val prevDx = (curr.flooredX - prev.flooredX).coerceIn(-1, 1)
              val prevDy = (curr.flooredY - prev.flooredY).coerceIn(-1, 1)
              val prevDz = (curr.flooredZ - prev.flooredZ).coerceIn(-1, 1)
              val nextDx = (next.flooredX - curr.flooredX).coerceIn(-1, 1)
              val nextDy = (next.flooredY - curr.flooredY).coerceIn(-1, 1)
              val nextDz = (next.flooredZ - curr.flooredZ).coerceIn(-1, 1)

              if (prevDx == nextDx && prevDy == nextDy && prevDz == nextDz) {
                continue
              }
              simplified.add(curr)
            }

            simplified.add(positions.last())
            PathImpl(rawPath.getStart(), rawPath.getEnd(), simplified)
          }
        currentPath = path
        currentWaypointIndex = 0

        ChatUtils.sendMessage(
          "§aPath found! §7Calculated in §f${duration}ms §8(${path.length()} nodes)"
        )
      }
    }
  }

  fun stop() {
    currentPath = null
    currentWaypointIndex = 0
  }

  /*
   * as of writing this, there is intentionally no moving, rotations,
   * or jumping yet. i ask that you make sure that the algo works good
   * before implementing them, aswell as rots
   */
  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val path = currentPath ?: return
    val player = mc.player ?: return

    val waypoints = path.collect().toList()
    if (currentWaypointIndex >= waypoints.size) {
      ChatUtils.sendMessage("Reached the end!") // this is kinda icky, someone change this lol
      stop()
      return
    }

    val targetPos = waypoints[currentWaypointIndex].mid()
    val targetVec = Vec3(targetPos.x, targetPos.y, targetPos.z)

    val horizontalDistSq =
      (player.x - targetVec.x) * (player.x - targetVec.x) +
        (player.z - targetVec.z) * (player.z - targetVec.z)
    if (horizontalDistSq < 0.25) {
      currentWaypointIndex++
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val path = currentPath ?: return
    val waypoints = path.collect().toList()

    if (waypoints.size < 2) return

    for (i in 0 until waypoints.size - 1) {
      val start = waypoints[i].mid()
      val end = waypoints[i + 1].mid()

      Render3D.drawLine(
        event.context,
        Vec3(start.x, start.y, start.z),
        Vec3(end.x, end.y, end.z),
        Color.CYAN,
        true,
        2.0f
      )
    }

    if (currentWaypointIndex < waypoints.size) {
      val currentPos = waypoints[currentWaypointIndex].mid()
      Render3D.drawBox(
        event.context,
        AABB(
          currentPos.x - 0.25,
          currentPos.y - 0.25,
          currentPos.z - 0.25,
          currentPos.x + 0.25,
          currentPos.y + 0.25,
          currentPos.z + 0.25
        ),
        Color.GREEN,
        true
      )
    }
  }

}
