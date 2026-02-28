package org.cobalt.internal.pathfinding

import org.cobalt.api.pathfinder.pathing.INeighborStrategy
import org.cobalt.api.pathfinder.pathing.Pathfinder
import org.cobalt.api.pathfinder.pathing.configuration.PathfinderConfiguration
import org.cobalt.api.pathfinder.pathing.heuristic.HeuristicWeights
import org.cobalt.api.pathfinder.pathing.heuristic.IHeuristicStrategy
import org.cobalt.api.pathfinder.pathing.processing.NodeProcessor
import org.cobalt.api.pathfinder.provider.NavigationPointProvider

data class PathPlanProfile(
  val id: String,
  val maxIterations: Int,
  val maxLength: Int,
  val async: Boolean,
  val fallback: Boolean,
  val providerFactory: () -> NavigationPointProvider,
  val neighborStrategy: INeighborStrategy,
  val heuristicWeights: HeuristicWeights,
  val heuristicStrategy: IHeuristicStrategy,
  val processorFactories: List<() -> NodeProcessor>,
  val pathfinderFactory: (PathfinderConfiguration) -> Pathfinder,
) {

  fun buildConfiguration(): PathfinderConfiguration {
    return PathfinderConfiguration(
      maxIterations = maxIterations,
      maxLength = maxLength,
      async = async,
      fallback = fallback,
      provider = providerFactory(),
      neighborStrategy = neighborStrategy,
      heuristicWeights = heuristicWeights,
      heuristicStrategy = heuristicStrategy,
      processors = processorFactories.map { it() }
    )
  }
}
