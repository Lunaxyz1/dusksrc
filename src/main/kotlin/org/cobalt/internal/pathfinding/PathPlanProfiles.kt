package org.cobalt.internal.pathfinding

import org.cobalt.api.pathfinder.pathfinder.AStarPathfinder
import org.cobalt.api.pathfinder.pathing.heuristic.HeuristicWeights
import org.cobalt.api.pathfinder.pathing.heuristic.LinearHeuristicStrategy
import org.cobalt.api.pathfinder.pathing.minecraft.MinecraftParkourNeighborStrategy
import org.cobalt.api.pathfinder.pathing.processing.impl.AvoidanceProcessor
import org.cobalt.api.pathfinder.pathing.processing.impl.MinecraftParkourProcessor
import org.cobalt.api.pathfinder.pathing.processing.impl.MinecraftPathProcessor
import org.cobalt.api.pathfinder.provider.impl.MinecraftNavigationProvider

object PathPlanProfiles {

  const val DEFAULT_ID = "default"

  val DEFAULT: PathPlanProfile =
    PathPlanProfile(
      id = DEFAULT_ID,
      maxIterations = 12000,
      maxLength = 0,
      async = false,
      fallback = true,
      providerFactory = { MinecraftNavigationProvider() },
      neighborStrategy = MinecraftParkourNeighborStrategy,
      heuristicWeights = HeuristicWeights(1.0, 0.0, 0.0, 0.5),
      heuristicStrategy = LinearHeuristicStrategy(),
      processorFactories = listOf(
        { AvoidanceProcessor() },
        { MinecraftParkourProcessor() },
        { MinecraftPathProcessor() }
      ),
      pathfinderFactory = { config -> AStarPathfinder(config) }
    )
}
