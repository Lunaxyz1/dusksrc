package org.cobalt.internal.pathfinding

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.player.Input
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec2
import org.apache.logging.log4j.LogManager
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.pathfinder.pathing.result.PathState
import org.cobalt.api.pathfinder.pathing.processing.impl.AvoidanceCache
import org.cobalt.api.pathfinder.wrapper.PathPosition
import org.cobalt.api.util.ChatUtils

object DuskPathfinder {
private const val MAX_REPATH_DISTANCE = 6.0
private const val WAYPOINT_DISTANCE = 0.35
private const val CHECKPOINT_STEP = 4
private const val CHECKPOINT_REACHED_DISTANCE = 1.2
private const val FINAL_ARRIVE_DISTANCE = 1.8
private const val AUTO_STEER_MAX_DEGREES = 45.0
private const val AUTO_STEER_STRENGTH = 0.35
private const val HUMANIZE_MOVEMENT = true
private const val SMOOTH_ROTATE = true
private const val RANDOM_PAUSES = false
private const val PAUSE_CHANCE = 0.02f
private const val PAUSE_TICKS_MIN = 4
private const val PAUSE_TICKS_MAX = 10
private const val RANDOM_YAW_JITTER = false
private const val JITTER_DEGREES = 2.0
private const val DEBUG_HUD = false
private const val DEBUG_LOG = false
private const val DEBUG_CHAT = false
private const val DEBUG_TICK_CHAT = false
private const val DEBUG_TICK_FILE = false
private const val DEBUG_LOG_INTERVAL_TICKS = 20
private const val STRAFE_DEADZONE = 0.15f
private const val ROTATE_ONLY_YAW_DEGREES = 100.0
private const val ROTATE_TARGET_RESPONSE = 8.0
private const val RENDER_ROTATE_RESPONSE = 18.0
private const val RENDER_PITCH_RESPONSE = 14.0
private const val PITCH_FLATTEN_DY = 1
private const val PITCH_MAX_DEGREES = 45.0
private const val WATER_ESCAPE_RADIUS = 6
private const val WATER_ESCAPE_REPATH_TICKS = 10
private const val BACKWARD_ALLOW_TICKS = 20
private const val ESCAPE_REPATH_RADIUS = 4
private const val ESCAPE_REPATH_VERTICAL = 2
private const val NAVMESH_SCAN_RADIUS = 4
private const val NAVMESH_SCAN_VERTICAL = 2
private const val NAVMESH_REPATH_TICKS = 10
private const val PATH_TAG = "pathfinding"
private const val STUCK_TICKS_LIMIT = 15
private const val STUCK_MOVEMENT_EPS = 5.0e-5
private const val REPATH_INTERVAL_TICKS = 20
private const val PASSED_DOT_RATIO = 0.1
private const val PASSED_DISTANCE_EPS = 0.5
private const val SPLINE_ADVANCE_EPS = 0.25
private const val BEHIND_YAW_DEGREES = 120.0
private const val BEHIND_TICKS_LIMIT = 10
private const val BEHIND_DIST_EPS = 0.05
private const val SPLINE_FOLLOW_EPS = 0.35
private const val SPLINE_LOOKAHEAD = 0.85
private const val BELOW_TARGET_Y_GAP = 2
private const val BELOW_TICKS_LIMIT = 20
private const val NEARBY_REPATH_RADIUS = 2
private const val BLOCKED_REPATH_COOLDOWN_TICKS = 20
private const val BLOCKED_REPATH_FAIL_LIMIT = 2
private const val JUMP_HOLD_TICKS = 3
private const val JUMP_COOLDOWN_TICKS = 4
private const val EARLY_JUMP_DISTANCE = 3.2
private const val STEPUP_JUMP_DISTANCE = 1.4
private const val CLIMB_JUMP_DISTANCE = 2.2
private const val PATH_CACHE_TTL_TICKS = 60
private const val PATH_CACHE_MAX = 512
private const val AVOID_STUCK_TTL_TICKS = 400L
private const val AVOID_STUCK_RADIUS = 1

private var target: BlockPos? = null
private var path: MutableList<BlockPos> = mutableListOf()
private var pathIndex = 0
private var active = false
private var mainPath: List<BlockPos> = emptyList()
private var mainPathIndex = 0
private var checkpoints: List<BlockPos> = emptyList()
private var checkpointIndex = 0
private var recoveryTargetIndex = -1
private var moveVectorField: Field? = null
private var keyPressesField: Field? = null
private var inputClass: Class<*>? = null
private var stuckTicks = 0
private var lastPosX = 0.0
private var lastPosY = 0.0
private var lastPosZ = 0.0
private var hasLastPos = false
private var debugForward = 0f
private var debugStrafe = 0f
private var debugJump = false
private var debugDist2 = 0.0
private var debugDy = 0
private var debugFlip = false
private var debugDot = 0.0
private var debugDelta = 0.0
private var debugTargetX = 0.0
private var debugTargetY = 0.0
private var debugTargetZ = 0.0
private var debugTargetYaw = 0.0
private var debugTargetPitch = 0.0
private var debugLookYaw = 0.0
private var debugLookPitch = 0.0
private var debugSplineYaw = 0.0
private var debugYawError = 0.0
private var debugPitchError = 0.0
private var debugCurrentPitch = 0.0
private var debugStrafeScale = 0.0
private var debugRepathReason = "-"
private var debugRepathTick = 0L
private var debugLogTick = 0L
private var smoothedForward = 0f
private var smoothedStrafe = 0f
private var smoothedTargetYaw: Double? = null
private var smoothedTargetPitch: Double? = null
private var lastRotateNs = 0L
private var lastRenderRotateNs = 0L
private var desiredYaw: Double? = null
private var desiredPitch: Double? = null
private var pauseTicks = 0
private var behindTicks = 0
private var lastDist2 = Double.NaN
private var belowTicks = 0
private var jumpHoldTicks = 0
private var jumpCooldownTicks = 0
private var recovering = false
private var recoveryGoal: BlockPos? = null
private var allowBackwardTicks = 0
private var waterEscapeTarget: BlockPos? = null
private var waterEscapeActive = false
private var lastNavMeshTick = 0L
private var blockedRepathUntilTick = 0L
private var blockedRepathFails = 0
private val rng = kotlin.random.Random.Default

private val logger = LogManager.getLogger("dutt-client")

private val profiles =
	ConcurrentHashMap<String, PathPlanProfile>().apply {
		this[PathPlanProfiles.DEFAULT.id] = PathPlanProfiles.DEFAULT
	}

private data class PathCacheKey(val dimension: String, val profileId: String, val start: Long, val goal: Long)
private data class PathCacheEntry(val path: List<BlockPos>, val createdAtTick: Long)

private val pathCache =
	object : LinkedHashMap<PathCacheKey, PathCacheEntry>(256, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PathCacheKey, PathCacheEntry>): Boolean {
			return size > PATH_CACHE_MAX
		}
	}

@Volatile
private var defaultProfileId: String = PathPlanProfiles.DEFAULT.id

private var currentProfileId: String = defaultProfileId

	fun isActive(): Boolean = active

	fun registerProfile(profile: PathPlanProfile) {
		profiles[profile.id] = profile
	}

	fun setDefaultProfile(id: String) {
		if (profiles.containsKey(id)) {
			defaultProfileId = id
		} else {
			logger.warn("Unknown pathfinding profile id={}", id)
		}
	}

	private fun resolveProfile(id: String): PathPlanProfile {
		return profiles[id] ?: profiles[defaultProfileId] ?: PathPlanProfiles.DEFAULT
	}

	fun startAtLookedBlock(client: Minecraft): Boolean {
		val hit = client.hitResult
		if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
			return start(client, hit.blockPos)
		}
		notify(client, "Look at a block first.")
		return false
	}

fun start(client: Minecraft, rawTarget: BlockPos): Boolean {
	return start(client, rawTarget, defaultProfileId)
}

fun start(client: Minecraft, rawTarget: BlockPos, profileId: String): Boolean {
	val level = client.level ?: return false
	val player = client.player ?: return false
	val start = player.blockPosition()
	val resolvedTarget = MinecraftPathingRules.resolveTarget(level, rawTarget) ?: run {
		notify(client, "Target is not walkable.")
		DebugLog.status(client, "Path", "Failed: target not walkable.")
		return false
	}
	if (start == resolvedTarget) {
		notify(client, "Already at target.")
		DebugLog.status(client, "Path", "Failed: already at target.")
		return false
	}

	val profile = resolveProfile(profileId)
	currentProfileId = profile.id
	val newPath = planPath(level, start, resolvedTarget, profile)
	if (newPath.isEmpty()) {
		notify(client, "No path found.")
		DebugLog.status(client, "Path", "Failed: no path found.")
		return false
	}
	DebugLog.startSession(client, "Path")
	target = resolvedTarget
	path = newPath.toMutableList()
	pathIndex = 0
	checkpoints = buildCheckpoints(path)
	checkpointIndex = 0
	active = true
	mainPath = path.toList()
	mainPathIndex = 0
	recoveryTargetIndex = -1
	recovering = false
	recoveryGoal = null
	resetStuck()
	OverlayRenderEngine.setEnabled(true)
	notify(client, "Pathing to ${resolvedTarget.x} ${resolvedTarget.y} ${resolvedTarget.z}.")
	DebugLog.status(
		client,
		"Path",
		"Started -> ${resolvedTarget.x} ${resolvedTarget.y} ${resolvedTarget.z} from ${start.x} ${start.y} ${start.z}"
	)
	return true
}

	fun stop(client: Minecraft, reason: String? = null) {
		if (!active) {
			return
		}
	active = false
	target = null
	path.clear()
	pathIndex = 0
	mainPath = emptyList()
	mainPathIndex = 0
	checkpoints = emptyList()
	checkpointIndex = 0
	recovering = false
	recoveryGoal = null
	recoveryTargetIndex = -1
	OverlayRenderEngine.clearTag(PATH_TAG)
	resetStuck()
	resetInput(client)
		val finalReason = reason ?: "Stopped."
		val endPos = client.player?.blockPosition()
		val endText = if (endPos != null) " at ${endPos.x} ${endPos.y} ${endPos.z}" else ""
		if (reason != null) {
			notify(client, reason)
		}
		DebugLog.status(client, "Path", "Ended: $finalReason$endText")
		DebugLog.endSession(client, "Path")
	}

	fun tick(client: Minecraft) {
		if (!active) {
			return
		}
		val player = client.player ?: return
		val level = client.level ?: return
		val currentTarget = target ?: return
		DebugLog.debugTickFile(
			client,
			"Path",
			"pos:${"%.2f".format(player.x)},${"%.2f".format(player.y)},${"%.2f".format(player.z)} " +
				"blk:${player.blockX},${player.blockY},${player.blockZ}",
			level.gameTime
		)
		if (allowBackwardTicks > 0) {
			allowBackwardTicks--
		}

	if (client.screen != null) {
		resetInput(client)
		updateLastPos(player)
		return
	}

	updateCheckpointProgress(client, level, player)
	if (handleWaterEscape(client, level, player, currentTarget)) {
		updateLastPos(player)
		return
	}
	if (handleNavMeshRecovery(client, level, player, currentTarget, false)) {
		updateLastPos(player)
		return
	}
	if (recovering) {
		val recoveryTarget = recoveryGoal
		if (recoveryTarget != null) {
			val recoveryDist = distanceSq(player, recoveryTarget)
			if (recoveryDist <= CHECKPOINT_REACHED_DISTANCE * CHECKPOINT_REACHED_DISTANCE) {
				finishRecovery(client, level, player, currentTarget, "recovery-complete")
				updateLastPos(player)
				return
			}
		}
	}
	val nowTick = level.gameTime
	try {
		PathOverlayRenderer.updateOverlays(level, player, checkpoints, checkpointIndex, active, PATH_TAG)
	} catch (_: Exception) {
		OverlayRenderEngine.setEnabled(false)
	}

	if (pathIndex >= path.size) {
		val finalTarget = target
		if (finalTarget != null) {
			if (recovering) {
				finishRecovery(client, level, player, finalTarget, "resume-recover")
				updateLastPos(player)
				return
			}
			if (!isAtTarget(player, finalTarget, FINAL_ARRIVE_DISTANCE)) {
				attemptRepath(client, level, player, "resume", finalTarget, hard = true)
				updateLastPos(player)
				return
			}
		}
		stop(client, "Arrived.")
		return
	}

	if (!recovering && checkFinalArrival(client, player)) {
		stop(client, "Arrived.")
		return
	}

		val finalTarget = target
		val waypoint =
			if (!recovering && finalTarget != null && pathIndex >= path.size - 1) {
				finalTarget
			} else {
				path[pathIndex]
			}
		if (shouldPrioritizeEscape(client, level, player, waypoint)) {
			updateLastPos(player)
			return
		}

		if (checkPassedCheckpointRepath(client, level, player, currentTarget)) {
			updateLastPos(player)
			return
		}
		val spline = resolveSplineTarget(player)
		val targetX = spline?.x ?: (waypoint.x + 0.5)
		val targetY = spline?.y ?: (waypoint.y + 0.5)
		val targetZ = spline?.z ?: (waypoint.z + 0.5)
		val dx = targetX - player.x
		val dz = targetZ - player.z
		val dy = waypoint.y - player.blockY
		debugTargetX = targetX
		debugTargetY = targetY
		debugTargetZ = targetZ
		val waypointX = waypoint.x + 0.5
		val waypointY = waypoint.y + 0.5
		val waypointZ = waypoint.z + 0.5
		val (targetYaw, targetPitch) = computeYawPitch(player.x, player.y, player.z, waypointX, waypointY, waypointZ)
		val (lookYaw, lookPitch) = computeYawPitch(player.x, player.y, player.z, targetX, targetY, targetZ)
		val splineYaw = if (spline != null) lookYaw else targetYaw
		debugTargetYaw = targetYaw
		debugTargetPitch = targetPitch
		debugLookYaw = lookYaw
		debugLookPitch = lookPitch
		debugSplineYaw = splineYaw
		debugCurrentPitch = player.xRot.toDouble()
		debugYawError = wrapDegrees(lookYaw - player.yRot.toDouble())
		debugPitchError = lookPitch - player.xRot.toDouble()

		val gameTime = level.gameTime
		if (!recovering && gameTime % REPATH_INTERVAL_TICKS == 0L) {
			val currentEstimate = heuristic(player.blockPosition(), currentTarget)
			val pathEstimate = heuristic(waypoint, currentTarget)
			if (currentEstimate + 2 < pathEstimate) {
				attemptRepath(client, level, player, "shortcut", currentTarget, hard = false)
			}
		}
		var blockedRepathTriggered = false
		if (!recovering && !MinecraftPathingRules.isWalkable(level, waypoint)) {
			if (nowTick >= blockedRepathUntilTick) {
				val changed = attemptRepath(client, level, player, "blocked", currentTarget, hard = false, allowSame = false)
				blockedRepathTriggered = changed
				blockedRepathUntilTick = nowTick + BLOCKED_REPATH_COOLDOWN_TICKS
				if (changed) {
					blockedRepathFails = 0
				} else {
					blockedRepathFails++
					if (blockedRepathFails >= BLOCKED_REPATH_FAIL_LIMIT) {
						blockedRepathFails = 0
					}
				}
			}
		} else {
			blockedRepathFails = 0
		}
		val dist2 = dx * dx + dz * dz
		val waypointDx = waypoint.x + 0.5 - player.x
		val waypointDz = waypoint.z + 0.5 - player.z
		val waypointDist2 = waypointDx * waypointDx + waypointDz * waypointDz
		val reachedHorizontal = waypointDist2 < WAYPOINT_DISTANCE * WAYPOINT_DISTANCE
		val reachedVertical = if (dy > 0) player.blockY >= waypoint.y else abs(dy) <= 1

	if (reachedHorizontal && reachedVertical) {
		pathIndex++
		if (pathIndex >= path.size) {
			val finalTarget = target
			if (finalTarget != null && recovering) {
				finishRecovery(client, level, player, finalTarget, "resume-recover")
				updateLastPos(player)
				return
			}
			stop(client, "Arrived.")
		}
		if (!recovering) {
			mainPathIndex = pathIndex
		}
		updateLastPos(player)
		return
	}

	val dist = sqrt(max(waypointDist2, 0.0001))
	if (dist > MAX_REPATH_DISTANCE) {
		if (!recovering) {
			attemptRepath(client, level, player, "distance", currentTarget, hard = true)
			updateLastPos(player)
			return
		}
	}

	if (handleStuck(client, level, player, waypointDist2, waypoint)) {
		updateLastPos(player)
		return
	}
	if (handleBelowRecovery(client, level, player, waypoint)) {
		updateLastPos(player)
		return
	}
	if (blockedRepathTriggered) {
		updateLastPos(player)
		return
	}

		if (RANDOM_PAUSES) {
			if (pauseTicks > 0) {
				pauseTicks--
				applyMovement(client, player.input, 0f, 0f, false, false, false)
				updateLastPos(player)
				return
			}
			if (rng.nextFloat() < PAUSE_CHANCE) {
				pauseTicks = rng.nextInt(PAUSE_TICKS_MIN, PAUSE_TICKS_MAX + 1)
				applyMovement(client, player.input, 0f, 0f, false, false, false)
				updateLastPos(player)
				return
			}
		}

		if (SMOOTH_ROTATE) {
			val targetYaw = (atan2(-dx, dz) * (180.0 / Math.PI)).toFloat()
			smoothRotate(player, targetYaw)
		}
		val move = computeMoveInput(player.yRot.toDouble(), dx, dz)
		if (jumpCooldownTicks > 0) {
			jumpCooldownTicks--
		}
		val jumpRequest = shouldJump(level, player, waypointDx, waypointDz, dy, waypoint)
		val climbJump =
			dy > 0 &&
				player.onGround() &&
				waypointDist2 <= CLIMB_JUMP_DISTANCE * CLIMB_JUMP_DISTANCE &&
				MinecraftPathingRules.isPassable(level, player.blockPosition().above())
		if (jumpRequest && dy > 0 && player.onGround() && jumpCooldownTicks > 0) {
			jumpCooldownTicks = 0
		}
		if (climbJump) {
			jumpHoldTicks = max(jumpHoldTicks, JUMP_HOLD_TICKS)
			jumpCooldownTicks = 0
		}
		if (jumpRequest && jumpCooldownTicks == 0) {
			jumpHoldTicks = JUMP_HOLD_TICKS
			jumpCooldownTicks = JUMP_COOLDOWN_TICKS
		}
		val jump = jumpHoldTicks > 0
		if (jumpHoldTicks > 0) {
			jumpHoldTicks--
		}
	debugForward = move.second
	debugStrafe = move.first
	debugJump = jump || jumpRequest
	debugDist2 = dist2
	debugDy = dy
		if (!recovering && handleBehindRepath(client, level, player, currentTarget, dist2)) {
			updateLastPos(player)
			return
		}
	val sprint = shouldSprint(player, move.second)
		applyMovement(client, player.input, move.first, move.second, jump, false, sprint)
		writeDebug(client, player)
		updateLastPos(player)
	}

	fun render(context: org.cobalt.api.event.impl.render.WorldRenderContext) {
		OverlayRenderEngine.render(context)
	}

	fun renderTick() {
		if (!active) {
			desiredYaw = null
			desiredPitch = null
			lastRenderRotateNs = 0L
			return
		}
		val player = Minecraft.getInstance().player ?: return
		val yawTarget = desiredYaw ?: return
		val pitchTarget = desiredPitch ?: return
		val now = System.nanoTime()
		val dt =
			if (lastRenderRotateNs == 0L) {
				1.0 / 60.0
			} else {
				((now - lastRenderRotateNs) / 1_000_000_000.0).coerceIn(1.0 / 240.0, 0.10)
			}
		lastRenderRotateNs = now

		val yawDelta = wrapDegrees(yawTarget - player.yRot.toDouble())
		val yawAlpha = 1.0 - exp(-dt * RENDER_ROTATE_RESPONSE)
		player.yRot = (player.yRot + yawDelta * yawAlpha).toFloat()

		val pitchDelta = pitchTarget - player.xRot.toDouble()
		val pitchAlpha = 1.0 - exp(-dt * RENDER_PITCH_RESPONSE)
		player.xRot = (player.xRot + pitchDelta * pitchAlpha).toFloat().coerceIn(-89.9f, 89.9f)
	}

	private fun resetInput(client: Minecraft) {
		val player = client.player ?: return
		debugForward = 0f
		debugStrafe = 0f
		debugJump = false
		smoothedForward = 0f
		smoothedStrafe = 0f
		smoothedTargetYaw = null
		smoothedTargetPitch = null
		lastRotateNs = 0L
		lastRenderRotateNs = 0L
		desiredYaw = null
		desiredPitch = null
		jumpHoldTicks = 0
		jumpCooldownTicks = 0
		allowBackwardTicks = 0
		waterEscapeTarget = null
		waterEscapeActive = false
		lastNavMeshTick = 0L
		applyMovement(client, player.input, 0f, 0f, false, false, false)
	}

	private fun planPath(level: Level, start: BlockPos, goal: BlockPos, profile: PathPlanProfile): List<BlockPos> {
		val dimension = level.dimension().toString()
		val cacheKey = PathCacheKey(dimension, profile.id, start.asLong(), goal.asLong())
		val cached = pathCache[cacheKey]
		if (cached != null) {
			val age = level.gameTime - cached.createdAtTick
			if (age <= PATH_CACHE_TTL_TICKS && cached.path.none { AvoidanceCache.isAvoided(level, it) }) {
				return cached.path
			}
			pathCache.remove(cacheKey)
		}
		val config = profile.buildConfiguration()
		val pathfinder = profile.pathfinderFactory(config)
		val startPos = PathPosition(start.x.toDouble(), start.y.toDouble(), start.z.toDouble())
		val targetPos = PathPosition(goal.x.toDouble(), goal.y.toDouble(), goal.z.toDouble())
		val result =
			try {
				pathfinder.findPath(startPos, targetPos).toCompletableFuture().join()
			} catch (_: Exception) {
				return emptyList()
			}
		val state = result.getPathState()
		if (state == PathState.FAILED || state == PathState.ABORTED) {
			return emptyList()
		}
		val positions = result.getPath().collect()
		if (positions.isEmpty()) {
			return emptyList()
		}
		val nodes = positions.map { BlockPos(it.flooredX, it.flooredY, it.flooredZ) }
		val simplified = simplifyPath(nodes)
		pathCache[cacheKey] = PathCacheEntry(simplified, level.gameTime)
		return simplified
	}

	private fun simplifyPath(nodes: List<BlockPos>): List<BlockPos> {
		if (nodes.size <= 2) {
			return nodes
		}
		val simplified = ArrayList<BlockPos>(nodes.size)
		simplified.add(nodes.first())
		for (i in 1 until nodes.size - 1) {
			val prev = simplified.last()
			val curr = nodes[i]
			val next = nodes[i + 1]

			val prevDx = (curr.x - prev.x).coerceIn(-1, 1)
			val prevDy = (curr.y - prev.y).coerceIn(-1, 1)
			val prevDz = (curr.z - prev.z).coerceIn(-1, 1)
			val nextDx = (next.x - curr.x).coerceIn(-1, 1)
			val nextDy = (next.y - curr.y).coerceIn(-1, 1)
			val nextDz = (next.z - curr.z).coerceIn(-1, 1)

			if (prevDx == nextDx && prevDy == nextDy && prevDz == nextDz) {
				continue
			}
			simplified.add(curr)
		}
		simplified.add(nodes.last())
		return simplified
	}

	private fun heuristic(a: BlockPos, b: BlockPos): Int {
		return abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z)
	}

private fun buildCheckpoints(nodes: List<BlockPos>): List<BlockPos> {
	if (nodes.isEmpty()) {
		return emptyList()
	}
	val markers = ArrayList<BlockPos>()
	var steps = 0
	for (i in 1 until nodes.size) {
		steps++
		val node = nodes[i]
		if (steps >= CHECKPOINT_STEP || i == nodes.lastIndex) {
			markers.add(node)
			steps = 0
		}
	}
	return markers
}

private fun currentCheckpoint(): BlockPos? {
	if (checkpoints.isEmpty()) {
		return null
	}
	val idx = checkpointIndex.coerceIn(0, checkpoints.lastIndex)
	return checkpoints[idx]
}

private fun updateCheckpointProgress(client: Minecraft, level: Level, player: net.minecraft.client.player.LocalPlayer) {
	if (checkpoints.isEmpty()) {
		return
	}
	val current = checkpoints[checkpointIndex]
	val currentDistSq = distanceSq(player, current)
	val minDistSq = CHECKPOINT_REACHED_DISTANCE * CHECKPOINT_REACHED_DISTANCE
	if (currentDistSq <= minDistSq) {
		advanceCheckpoint(client, level, player)
		return
	}
	if (checkpointIndex < checkpoints.lastIndex) {
		val next = checkpoints[checkpointIndex + 1]
		val nextDistSq = distanceSq(player, next)
		if (nextDistSq + SPLINE_ADVANCE_EPS * SPLINE_ADVANCE_EPS < currentDistSq) {
			advanceCheckpoint(client, level, player)
		}
	}
}

private fun advanceCheckpoint(client: Minecraft, level: Level, player: net.minecraft.client.player.LocalPlayer) {
	if (checkpointIndex >= checkpoints.lastIndex) {
		return
	}
	checkpointIndex++
	val finalTarget = target
	if (!recovering && finalTarget != null) {
		if (debugRepathTick == 0L || level.gameTime - debugRepathTick >= REPATH_INTERVAL_TICKS) {
			attemptRepath(client, level, player, "checkpoint", finalTarget, hard = false)
		}
	}
}

private fun distanceSq(player: net.minecraft.client.player.LocalPlayer, pos: BlockPos): Double {
	val dx = player.x - (pos.x + 0.5)
	val dy = player.y - (pos.y + 0.5)
	val dz = player.z - (pos.z + 0.5)
	return dx * dx + dy * dy + dz * dz
}

private fun checkFinalArrival(client: Minecraft, player: net.minecraft.client.player.LocalPlayer): Boolean {
	if (recovering) {
		return false
	}
	val finalTarget = target
	if (finalTarget != null) {
		if (isAtTarget(player, finalTarget, FINAL_ARRIVE_DISTANCE)) {
			return true
		}
	}
	if (path.isNotEmpty() && pathIndex >= path.lastIndex) {
		val last = path.last()
		if (isAtTarget(player, last, CHECKPOINT_REACHED_DISTANCE)) {
			return true
		}
	}
	return false
}

private fun isAtTarget(
	player: net.minecraft.client.player.LocalPlayer,
	pos: BlockPos,
	distance: Double = CHECKPOINT_REACHED_DISTANCE
): Boolean {
	val distSq = distanceSq(player, pos)
	val minSq = distance * distance
	val dy = abs(player.blockY - pos.y)
	return distSq <= minSq && dy <= 1
}


private data class SplineTarget(val x: Double, val y: Double, val z: Double, val t: Double)

private fun resolveSplineTarget(player: net.minecraft.client.player.LocalPlayer): SplineTarget? {
	if (checkpoints.isEmpty()) {
		return null
	}
	if (checkpointIndex >= checkpoints.lastIndex) {
		return null
	}
	val a = checkpoints[checkpointIndex]
	val b = checkpoints[checkpointIndex + 1]
	val ax = a.x + 0.5
	val ay = a.y + 0.5
	val az = a.z + 0.5
	val bx = b.x + 0.5
	val by = b.y + 0.5
	val bz = b.z + 0.5
	val px = player.x
	val pz = player.z
	val abx = bx - ax
	val abz = bz - az
	val lenSq = abx * abx + abz * abz
	if (lenSq <= 1.0e-6) {
		return SplineTarget(ax, ay, az, 0.0)
	}
	val t = ((px - ax) * abx + (pz - az) * abz) / lenSq
	val clamped = t.coerceIn(0.0, 1.0)
	var useT = clamped
	val x = ax + abx * useT
	val z = az + abz * useT
	val dx = px - x
	val dz = pz - z
	if (dx * dx + dz * dz <= SPLINE_FOLLOW_EPS * SPLINE_FOLLOW_EPS) {
		val len = kotlin.math.sqrt(lenSq)
		val advance = (SPLINE_LOOKAHEAD / len).coerceAtLeast(0.0)
		useT = (useT + advance).coerceIn(0.0, 1.0)
	}
	val finalX = ax + abx * useT
	val finalZ = az + abz * useT
	val finalY = ay + (by - ay) * useT
	return SplineTarget(finalX, finalY, finalZ, useT)
}

private fun checkPassedCheckpointRepath(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	goal: BlockPos
): Boolean {
	if (recovering) {
		return false
	}
	if (debugRepathTick != 0L && level.gameTime - debugRepathTick < REPATH_INTERVAL_TICKS) {
		return false
	}
	if (checkpoints.isEmpty() || checkpointIndex >= checkpoints.lastIndex) {
		return false
	}
	val current = checkpoints[checkpointIndex]
	val next = checkpoints[checkpointIndex + 1]

	val ax = current.x + 0.5
	val az = current.z + 0.5
	val bx = next.x + 0.5
	val bz = next.z + 0.5
	val px = player.x
	val pz = player.z

	val abx = bx - ax
	val abz = bz - az
	val apx = px - ax
	val apz = pz - az
	val abLenSq = abx * abx + abz * abz
	if (abLenSq <= 1.0e-6) {
		return false
	}
	val dot = apx * abx + apz * abz
	if (dot <= 0.0) {
		return false
	}

	val distCurrentSq = apx * apx + apz * apz
	val dxNext = px - bx
	val dzNext = pz - bz
	val distNextSq = dxNext * dxNext + dzNext * dzNext
	val minDistSq = CHECKPOINT_REACHED_DISTANCE * CHECKPOINT_REACHED_DISTANCE
	if (distCurrentSq <= minDistSq) {
		return false
	}
	val dotRatio = dot / abLenSq
	if (dotRatio < PASSED_DOT_RATIO) {
		return false
	}
	if (distNextSq + PASSED_DISTANCE_EPS * PASSED_DISTANCE_EPS >= distCurrentSq) {
		return false
	}

	return attemptRepath(client, level, player, "passed", goal, hard = false)
}


	private fun notify(@Suppress("UNUSED_PARAMETER") client: Minecraft, text: String) {
		NotificationManager.queue("Pathfinding", text, 2000L)
	}

	private fun applyMovement(client: Minecraft, input: Any, strafe: Float, forward: Float, jump: Boolean, shift: Boolean, sprint: Boolean) {
		val targetForward = forward.coerceIn(-1f, 1f)
		val targetStrafe = strafe.coerceIn(-1f, 1f)
		val useForward: Float
		val useStrafe: Float
		if (HUMANIZE_MOVEMENT) {
			smoothedForward = approach(smoothedForward, targetForward, 0.2f, 0.3f)
			smoothedStrafe = approach(smoothedStrafe, targetStrafe, 0.2f, 0.3f)
			useForward = smoothedForward
			useStrafe = smoothedStrafe
		} else {
			useForward = targetForward
			useStrafe = targetStrafe
		}

		val forwardPressed = useForward > 0.01f
		val backwardPressed = useForward < -0.01f
		val leftPressed = useStrafe < -0.01f
		val rightPressed = useStrafe > 0.01f
		resolveInputFields(input)
		try {
			keyPressesField?.set(input, Input(forwardPressed, backwardPressed, leftPressed, rightPressed, jump, shift, sprint))
		} catch (_: Exception) {
			// ignore - input shape changed
		}
		try {
			moveVectorField?.set(input, Vec2(useStrafe, useForward))
		} catch (_: Exception) {
			// ignore - if reflection fails, input will be keyPresses only
		}
		val options = client.options
		options.keyUp.setDown(forwardPressed)
		options.keyDown.setDown(backwardPressed)
		options.keyLeft.setDown(leftPressed)
		options.keyRight.setDown(rightPressed)
		options.keyJump.setDown(jump)
		options.keyShift.setDown(shift)
		options.keySprint.setDown(sprint)
	}

private fun shouldJump(level: Level, player: net.minecraft.client.player.LocalPlayer, dx: Double, dz: Double, dy: Int, waypoint: BlockPos): Boolean {
	if (MinecraftPathingRules.isClimbable(level, player.blockPosition()) || MinecraftPathingRules.isClimbable(level, player.blockPosition().above())) {
		return dy > 0
	}
	if (!player.onGround()) {
		return false
	}
	val dir = if (abs(dx) >= abs(dz)) {
		if (dx >= 0) Direction.EAST else Direction.WEST
	} else {
		if (dz >= 0) Direction.SOUTH else Direction.NORTH
	}
	val front = player.blockPosition().relative(dir)
	val stepUp = front.above()
	val stepUp2 = stepUp.above()
	val dist = sqrt(dx * dx + dz * dz)
	if (dy > 0) {
		val needsStepUp = MinecraftPathingRules.isStandable(level, front) && MinecraftPathingRules.isPassable(level, stepUp) && MinecraftPathingRules.isPassable(level, stepUp2)
		return needsStepUp && dist <= STEPUP_JUMP_DISTANCE
	}
	return dist <= EARLY_JUMP_DISTANCE && isJumpEdge(level, player.blockPosition(), dir, waypoint)
}

private fun isJumpEdge(level: Level, pos: BlockPos, dir: Direction, waypoint: BlockPos): Boolean {
	for (len in 2..MinecraftPathingRules.MAX_JUMP_LENGTH) {
		val landingBase = pos.relative(dir, len)
		val resolved = MinecraftPathingRules.walkableAt(level, landingBase) ?: continue
		if (resolved != waypoint) {
			continue
		}
		if (MinecraftPathingRules.gapClear(level, pos, dir, len) && MinecraftPathingRules.hasGapBelow(level, pos, dir, len) && MinecraftPathingRules.hasRunway(level, pos, dir)) {
			return true
		}
	}
	return false
}

	private fun computeMoveInput(yawDegrees: Double, dx: Double, dz: Double): Pair<Float, Float> {
		val len = sqrt(dx * dx + dz * dz)
		if (len < 1.0e-4) {
			return 0f to 0f
		}
		val dirX = dx / len
		val dirZ = dz / len
		val targetYaw = Math.toDegrees(atan2(dz, dx)) - 90.0
		val delta = wrapDegrees(targetYaw - yawDegrees)
		debugDelta = delta
		val absDelta = kotlin.math.abs(delta)
		val allowBackward = allowBackwardTicks > 0
		if (absDelta > ROTATE_ONLY_YAW_DEGREES && !allowBackward) {
			// Let rotation catch up before moving when target is far behind.
			debugStrafeScale = 0.0
			debugDot = 0.0
			debugFlip = false
			return 0f to 0f
		}
		val clamped = delta.coerceIn(-AUTO_STEER_MAX_DEGREES, AUTO_STEER_MAX_DEGREES)
		val jitter = if (RANDOM_YAW_JITTER) rng.nextDouble(-JITTER_DEGREES, JITTER_DEGREES) else 0.0
		val effectiveYaw = yawDegrees + clamped * AUTO_STEER_STRENGTH + jitter
		val yawRad = Math.toRadians(effectiveYaw)
		val sinYaw = kotlin.math.sin(yawRad)
		val cosYaw = kotlin.math.cos(yawRad)
		val forwardX = -sinYaw
		val forwardZ = cosYaw
		val rightX = -cosYaw
		val rightZ = -sinYaw

		var forward = (dirX * forwardX + dirZ * forwardZ).toFloat().coerceIn(-1f, 1f)
		var strafe = (dirX * rightX + dirZ * rightZ).toFloat().coerceIn(-1f, 1f)

		val angleScale = ((absDelta - 6.0) / AUTO_STEER_MAX_DEGREES).coerceIn(0.0, 1.0)
		val distScale = ((len - 0.6) / 2.0).coerceIn(0.0, 1.0)
		val scale = (angleScale * distScale).toFloat()
		debugStrafeScale = scale.toDouble()

		if (!allowBackward && forward < 0f) {
			forward = 0f
		} else if (allowBackward) {
			forward = forward.coerceIn(-0.8f, 1f)
		}
		strafe *= scale
		if (kotlin.math.abs(strafe) < STRAFE_DEADZONE) strafe = 0f

		debugDot = forward.toDouble()
		debugFlip = forward < 0f
		return strafe to forward
	}

	private fun wrapDegrees(degrees: Double): Double {
		var d = degrees % 360.0
		if (d >= 180.0) d -= 360.0
		if (d < -180.0) d += 360.0
		return d
	}

	private fun computeYawPitch(
		fromX: Double,
		fromY: Double,
		fromZ: Double,
		toX: Double,
		toY: Double,
		toZ: Double
	): Pair<Double, Double> {
		val dx = toX - fromX
		val dz = toZ - fromZ
		val dy = toY - fromY
		val yaw = Math.toDegrees(atan2(-dx, dz))
		val horiz = sqrt(dx * dx + dz * dz)
		val pitch =
			if (horiz < 1.0e-6) {
				if (dy > 0) -90.0 else 90.0
			} else {
				-Math.toDegrees(atan2(dy, horiz))
			}
		return yaw to pitch
	}

	private fun resolveInputFields(input: Any) {
		val cls = input.javaClass
		if (cls == inputClass) return
		inputClass = cls
		keyPressesField = cls.declaredFields.firstOrNull { it.name == "keyPresses" }?.apply { isAccessible = true }
		moveVectorField = cls.declaredFields.firstOrNull { it.name == "moveVector" }?.apply { isAccessible = true }
	}

private fun handleStuck(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	dist2: Double,
	waypoint: BlockPos
): Boolean {
	if (!hasLastPos) {
		updateLastPos(player)
		return false
		}
		val dx = player.x - lastPosX
		val dy = player.y - lastPosY
		val dz = player.z - lastPosZ
		val movedSq2d = dx * dx + dz * dz
		val movedSq3d = movedSq2d + dy * dy
		val movementSq = if (MinecraftPathingRules.isClimbable(level, player.blockPosition()) || MinecraftPathingRules.isClimbable(level, player.blockPosition().above())) {
			movedSq3d
		} else {
			movedSq2d
		}
		if (movementSq < STUCK_MOVEMENT_EPS && dist2 > WAYPOINT_DISTANCE * WAYPOINT_DISTANCE) {
			stuckTicks++
		} else {
			stuckTicks = 0
		}
		if (stuckTicks <= STUCK_TICKS_LIMIT) {
			return false
		}
		AvoidanceCache.mark(level, player.blockPosition(), AVOID_STUCK_TTL_TICKS, AVOID_STUCK_RADIUS)
		val tgt = target
		val targetText = if (tgt != null) " target=${tgt.x} ${tgt.y} ${tgt.z}" else ""
		DebugLog.status(
			client,
			"Path",
			"Stuck: no movement for ${stuckTicks} ticks at ${player.blockX} ${player.blockY} ${player.blockZ}$targetText"
		)
		if (attemptEscapeHole(client, level, player, waypoint)) {
			stuckTicks = 0
			return true
		}
		val finalTarget = target
		if (!recovering && finalTarget != null) {
			if (attemptRepath(client, level, player, "stuck", finalTarget, hard = false, allowSame = false)) {
				stuckTicks = 0
				return true
			}
		}
		if (!recovering && finalTarget != null) {
			if (attemptEscapeRepath(client, level, player, finalTarget, "stuck-escape")) {
				stuckTicks = 0
				return true
			}
		}
		val hint = currentCheckpoint() ?: finalTarget ?: waypoint
		if (attemptRecoveryPath(client, level, player, hint)) {
			stuckTicks = 0
			return true
		}
		if (!recovering && finalTarget != null && attemptNearbyRepath(client, level, player, finalTarget, "stuck-nearby")) {
			stuckTicks = 0
			return true
		}
		stuckTicks = 0
		if (isTrapped(level, player)) {
			logger.info("Path dbg stuck: trapped, stopping pathfinding at pos={},{},{}", player.x, player.y, player.z)
			val tgt = target
			val targetText = if (tgt != null) " target=${tgt.x} ${tgt.y} ${tgt.z}" else ""
			DebugLog.status(
				client,
				"Path",
				"Failed: trapped in hole at ${player.blockX} ${player.blockY} ${player.blockZ}$targetText"
			)
			stop(client, "Stuck in a hole.")
			return true
		}
		val reTarget = target
		if (reTarget == null) {
			DebugLog.status(client, "Path", "Failed: no target for repath.")
			stop(client, "Stuck.")
			return true
		}
	if (recovering) {
		return false
	}
	return attemptRepath(client, level, player, "stuck", reTarget, hard = true, allowSame = false)
}

private fun attemptRecoveryPath(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	hintTarget: BlockPos
): Boolean {
	val origin = player.blockPosition()
	if (!recovering) {
		mainPath = path.toList()
		mainPathIndex = pathIndex
	}
	val recoveryIndex = findRecoveryIndex(level, origin, hintTarget)
	val recovery = if (recoveryIndex >= 0 && recoveryIndex < mainPath.size) {
		mainPath[recoveryIndex]
	} else {
		null
	}
	if (recovery == null) {
		DebugLog.status(client, "Path", "Recovery: no checkpoint.")
		return false
	}
	if (recovery == origin) {
		return false
	}
	val newPath = planPath(level, origin, recovery, resolveProfile(currentProfileId))
	if (newPath.isEmpty()) {
		DebugLog.status(client, "Path", "Recovery: no path.")
		return false
	}
	setRepath("recover", level)
	path = newPath.toMutableList()
	pathIndex = 0
	checkpoints = buildCheckpoints(path)
	checkpointIndex = 0
	smoothedTargetYaw = null
	recovering = true
	recoveryGoal = recovery
	recoveryTargetIndex = recoveryIndex
	allowBackwardTicks = BACKWARD_ALLOW_TICKS
	blockedRepathFails = 0
	blockedRepathUntilTick = level.gameTime + BLOCKED_REPATH_COOLDOWN_TICKS
	DebugLog.status(client, "Path", "Recovery: pathing to checkpoint ${recovery.x} ${recovery.y} ${recovery.z}")
	return true
}

private fun attemptNearbyRepath(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	goal: BlockPos,
	reason: String
): Boolean {
	val origin = player.blockPosition()
	val candidates = ArrayList<BlockPos>()
	for (dy in -1..1) {
		for (dx in -NEARBY_REPATH_RADIUS..NEARBY_REPATH_RADIUS) {
			for (dz in -NEARBY_REPATH_RADIUS..NEARBY_REPATH_RADIUS) {
				val pos = origin.offset(dx, dy, dz)
				if (MinecraftPathingRules.isWalkable(level, pos)) {
					if (isInWater(level, pos) || isInWater(level, pos.above())) {
						continue
					}
					if (AvoidanceCache.isAvoided(level, pos)) {
						continue
					}
					candidates.add(pos)
				}
			}
		}
	}
	if (candidates.isEmpty()) {
		DebugLog.status(client, "Path", "Repath $reason: no nearby candidates.")
		return false
	}
	var bestPath: List<BlockPos>? = null
	var bestScore = Double.POSITIVE_INFINITY
	for (start in candidates) {
		val pathTry = planPath(level, start, goal, resolveProfile(currentProfileId))
		if (pathTry.isEmpty()) {
			continue
		}
		val dist = start.distSqr(origin)
		val score = pathTry.size + dist * 2.0
		if (score < bestScore) {
			bestScore = score
			bestPath = pathTry
		}
	}
	if (bestPath == null) {
		DebugLog.status(client, "Path", "Repath $reason: no path from nearby start.")
		return false
	}
	if (!isSamePath(bestPath)) {
		path = bestPath.toMutableList()
		pathIndex = 0
		checkpoints = buildCheckpoints(path)
		checkpointIndex = 0
		smoothedTargetYaw = null
		recovering = false
		recoveryGoal = null
		recoveryTargetIndex = -1
		mainPath = path.toList()
		mainPathIndex = 0
		allowBackwardTicks = BACKWARD_ALLOW_TICKS
		blockedRepathFails = 0
		blockedRepathUntilTick = level.gameTime + BLOCKED_REPATH_COOLDOWN_TICKS
		DebugLog.status(client, "Path", "Repath $reason: ok (nearby start).")
		return true
	}
	return false
}

private fun attemptEscapeRepath(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	goal: BlockPos,
	reason: String
): Boolean {
	val origin = player.blockPosition()
	val candidates = ArrayList<BlockPos>()
	for (dy in -ESCAPE_REPATH_VERTICAL..ESCAPE_REPATH_VERTICAL) {
		for (dx in -ESCAPE_REPATH_RADIUS..ESCAPE_REPATH_RADIUS) {
			for (dz in -ESCAPE_REPATH_RADIUS..ESCAPE_REPATH_RADIUS) {
				if (dx == 0 && dy == 0 && dz == 0) {
					continue
				}
				val pos = origin.offset(dx, dy, dz)
				if (!MinecraftPathingRules.isWalkable(level, pos)) {
					continue
				}
				if (isInWater(level, pos) || isInWater(level, pos.above())) {
					continue
				}
				if (AvoidanceCache.isAvoided(level, pos)) {
					continue
				}
				candidates.add(pos)
			}
		}
	}
	if (candidates.isEmpty()) {
		DebugLog.status(client, "Path", "Repath $reason: no escape candidates.")
		return false
	}
	var bestPath: List<BlockPos>? = null
	var bestScore = Double.POSITIVE_INFINITY
	for (start in candidates) {
		val pathTry = planPath(level, start, goal, resolveProfile(currentProfileId))
		if (pathTry.isEmpty()) {
			continue
		}
		val dist = start.distSqr(origin)
		val score = pathTry.size + dist * 1.5
		if (score < bestScore) {
			bestScore = score
			bestPath = pathTry
		}
	}
	if (bestPath == null) {
		DebugLog.status(client, "Path", "Repath $reason: no escape path.")
		return false
	}
	if (!isSamePath(bestPath)) {
		path = bestPath.toMutableList()
		pathIndex = 0
		checkpoints = buildCheckpoints(path)
		checkpointIndex = 0
		smoothedTargetYaw = null
		recovering = false
		recoveryGoal = null
		recoveryTargetIndex = -1
		mainPath = path.toList()
		mainPathIndex = 0
		allowBackwardTicks = BACKWARD_ALLOW_TICKS
		blockedRepathFails = 0
		blockedRepathUntilTick = level.gameTime + BLOCKED_REPATH_COOLDOWN_TICKS
		DebugLog.status(client, "Path", "Repath $reason: ok (escape start).")
		return true
	}
	return false
}

private fun findRecoveryIndex(level: Level, origin: BlockPos, hintTarget: BlockPos): Int {
	if (mainPath.isEmpty()) {
		return -1
	}
	var bestIdx = -1
	var bestScore = Double.POSITIVE_INFINITY
	val preferUp = hintTarget.y > origin.y
	val startIdx = mainPathIndex.coerceIn(0, mainPath.lastIndex)
	for (i in startIdx..mainPath.lastIndex) {
		val cp = mainPath[i]
		if (!MinecraftPathingRules.isWalkable(level, cp)) {
			continue
		}
		val distToCp = cp.distSqr(origin)
		val distToHint = cp.distSqr(hintTarget)
		val heightBonus = if (preferUp && cp.y > origin.y) (cp.y - origin.y) * 8 else 0
		val progressPenalty = (i - startIdx).coerceAtLeast(0) * 0.35
		val score = distToCp + distToHint * 0.2 + progressPenalty - heightBonus
		if (score < bestScore) {
			bestScore = score
			bestIdx = i
		}
	}
	return bestIdx
}

private fun handleBelowRecovery(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	waypoint: BlockPos
): Boolean {
	if (recovering) {
		return false
	}
	val hint = currentCheckpoint() ?: target ?: waypoint
	val targetY = hint.y
	if (player.blockY + BELOW_TARGET_Y_GAP <= targetY && player.onGround() && !MinecraftPathingRules.isClimbable(level, player.blockPosition())) {
		belowTicks++
	} else {
		belowTicks = 0
		return false
	}
	if (belowTicks < BELOW_TICKS_LIMIT) {
		return false
	}
	belowTicks = 0
	if (handleNavMeshRecovery(client, level, player, hint, true)) {
		return true
	}
	DebugLog.status(
		client,
		"Path",
		"Stuck: below path at ${player.blockX} ${player.blockY} ${player.blockZ} (waiting for stuck recovery)."
	)
	return false
}

private fun handleNavMeshRecovery(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	hintTarget: BlockPos,
	force: Boolean
): Boolean {
	if (recovering || waterEscapeActive) {
		return false
	}
	if (!player.onGround()) {
		return false
	}
	val pos = player.blockPosition()
	if (!force && MinecraftPathingRules.isWalkable(level, pos)) {
		return false
	}
	val now = level.gameTime
	if (now - lastNavMeshTick < NAVMESH_REPATH_TICKS) {
		return false
	}
	val anchor = findNavMeshAnchor(level, pos, hintTarget) ?: return false
	if (anchor == pos) {
		return false
	}
	val newPath = planPath(level, pos, anchor, resolveProfile(currentProfileId))
	if (newPath.isEmpty()) {
		return false
	}
	if (!recovering) {
		mainPath = path.toList()
		mainPathIndex = pathIndex
	}
	setRepath("navmesh", level)
	path = newPath.toMutableList()
	pathIndex = 0
	checkpoints = buildCheckpoints(path)
	checkpointIndex = 0
	smoothedTargetYaw = null
	recovering = true
	recoveryGoal = anchor
	recoveryTargetIndex = findRecoveryIndex(level, anchor, hintTarget)
	allowBackwardTicks = BACKWARD_ALLOW_TICKS
	lastNavMeshTick = now
	DebugLog.status(client, "Path", "Recovery: navmesh anchor ${anchor.x} ${anchor.y} ${anchor.z}")
	return true
}

private fun findNavMeshAnchor(level: Level, origin: BlockPos, target: BlockPos): BlockPos? {
	var best: BlockPos? = null
	var bestScore = Double.POSITIVE_INFINITY
	for (dy in -NAVMESH_SCAN_VERTICAL..NAVMESH_SCAN_VERTICAL) {
		for (dx in -NAVMESH_SCAN_RADIUS..NAVMESH_SCAN_RADIUS) {
			for (dz in -NAVMESH_SCAN_RADIUS..NAVMESH_SCAN_RADIUS) {
				val pos = origin.offset(dx, dy, dz)
				if (!MinecraftPathingRules.isWalkable(level, pos)) {
					continue
				}
				if (isInWater(level, pos) || isInWater(level, pos.above())) {
					continue
				}
				if (AvoidanceCache.isAvoided(level, pos)) {
					continue
				}
				val distOrigin = pos.distSqr(origin).toDouble()
				val distTarget = pos.distSqr(target).toDouble()
				val score = distOrigin + distTarget * 0.4
				if (score < bestScore) {
					bestScore = score
					best = pos
				}
			}
		}
	}
	return best
}

private fun handleWaterEscape(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	finalTarget: BlockPos
): Boolean {
	val pos = player.blockPosition()
	val inWater = isInWater(level, pos) || isInWater(level, pos.above())
	if (!inWater) {
		if (waterEscapeActive) {
			waterEscapeActive = false
			waterEscapeTarget = null
			if (!recovering) {
				attemptRepath(client, level, player, "water-exit", finalTarget, hard = false)
			}
		}
		return false
	}
	waterEscapeActive = true
	val existing = waterEscapeTarget
	val escape =
		if (existing != null && MinecraftPathingRules.isWalkable(level, existing) && !isInWater(level, existing)) {
			existing
		} else {
			findWaterEscapeTarget(level, pos)
		}
	if (escape == null) {
		applyMovement(client, player.input, 0f, 0f, true, false, false)
		return true
	}
	waterEscapeTarget = escape
	allowBackwardTicks = BACKWARD_ALLOW_TICKS
	val dx = escape.x + 0.5 - player.x
	val dz = escape.z + 0.5 - player.z
	if (SMOOTH_ROTATE) {
		val targetYaw = (atan2(-dx, dz) * (180.0 / Math.PI)).toFloat()
		smoothRotate(player, targetYaw)
	}
	val move = computeMoveInput(player.yRot.toDouble(), dx, dz)
	applyMovement(client, player.input, move.first, move.second, true, false, false)
	return true
}

private fun findWaterEscapeTarget(level: Level, origin: BlockPos): BlockPos? {
	var best: BlockPos? = null
	var bestScore = Double.POSITIVE_INFINITY
	for (dy in -1..ESCAPE_REPATH_VERTICAL) {
		for (dx in -WATER_ESCAPE_RADIUS..WATER_ESCAPE_RADIUS) {
			for (dz in -WATER_ESCAPE_RADIUS..WATER_ESCAPE_RADIUS) {
				if (dx == 0 && dy == 0 && dz == 0) {
					continue
				}
				val pos = origin.offset(dx, dy, dz)
				if (!MinecraftPathingRules.isWalkable(level, pos)) {
					continue
				}
				if (isInWater(level, pos) || isInWater(level, pos.above())) {
					continue
				}
				if (AvoidanceCache.isAvoided(level, pos)) {
					continue
				}
				val distSq = (dx * dx + dz * dz).toDouble()
				val heightPenalty = if (dy < 0) 2.0 else 0.0
				val score = distSq + heightPenalty
				if (score < bestScore) {
					bestScore = score
					best = pos
				}
			}
		}
	}
	return best
}

private fun isInWater(level: Level, pos: BlockPos): Boolean {
	return level.getFluidState(pos).`is`(FluidTags.WATER)
}

private fun attemptEscapeHole(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	waypoint: BlockPos
): Boolean {
	if (!player.onGround()) {
		return false
	}
	val pos = player.blockPosition()
	if (!MinecraftPathingRules.isPassable(level, pos.above()) || !MinecraftPathingRules.isPassable(level, pos.above(2))) {
		return false
	}
	var bestDir: Direction? = null
	var bestDist = Double.POSITIVE_INFINITY
	for (dir in Direction.Plane.HORIZONTAL) {
		val base = pos.relative(dir)
		val landing = base.above()
		if (!MinecraftPathingRules.isWalkable(level, landing)) {
			continue
		}
		val dx = (landing.x + 0.5) - (waypoint.x + 0.5)
		val dz = (landing.z + 0.5) - (waypoint.z + 0.5)
		val dist = dx * dx + dz * dz
		if (dist < bestDist) {
			bestDist = dist
			bestDir = dir
		}
	}
	if (bestDir == null) {
		return false
	}
	val escapePos = pos.relative(bestDir).above()
	val dx = escapePos.x + 0.5 - player.x
	val dz = escapePos.z + 0.5 - player.z
	if (SMOOTH_ROTATE) {
		val targetYaw = (atan2(-dx, dz) * (180.0 / Math.PI)).toFloat()
		smoothRotate(player, targetYaw)
	}
	val move = computeMoveInput(player.yRot.toDouble(), dx, dz)
	val forward = if (move.second <= 0f) 0.65f else move.second
	applyMovement(client, player.input, move.first, forward, true, false, false)
	return true
}

private fun finishRecovery(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	finalTarget: BlockPos,
	reason: String
): Boolean {
	if (recoveryTargetIndex >= 0 && recoveryTargetIndex < mainPath.size) {
		path = mainPath.toMutableList()
		pathIndex = recoveryTargetIndex
		checkpoints = buildCheckpoints(path)
		checkpointIndex =
			if (checkpoints.isEmpty()) {
				0
			} else {
				(pathIndex / CHECKPOINT_STEP).coerceIn(0, checkpoints.lastIndex)
			}
		recovering = false
		recoveryGoal = null
		recoveryTargetIndex = -1
		mainPathIndex = pathIndex
		DebugLog.status(client, "Path", "Recovery: rejoined main path at index ${pathIndex + 1}/${path.size}.")
		return true
	}
	val fallbackIndex = findRecoveryIndex(level, player.blockPosition(), finalTarget)
	if (fallbackIndex >= 0 && fallbackIndex < mainPath.size) {
		path = mainPath.toMutableList()
		pathIndex = fallbackIndex
		checkpoints = buildCheckpoints(path)
		checkpointIndex =
			if (checkpoints.isEmpty()) {
				0
			} else {
				(pathIndex / CHECKPOINT_STEP).coerceIn(0, checkpoints.lastIndex)
			}
		recovering = false
		recoveryGoal = null
		recoveryTargetIndex = -1
		mainPathIndex = pathIndex
		DebugLog.status(client, "Path", "Recovery: rejoined main path at index ${pathIndex + 1}/${path.size}.")
		return true
	}
	recovering = false
	recoveryGoal = null
	recoveryTargetIndex = -1
	return attemptRepath(client, level, player, reason, finalTarget, hard = true)
}

private fun isTrapped(level: Level, player: net.minecraft.client.player.LocalPlayer): Boolean {
	if (!player.onGround()) {
		return false
	}
	val pos = player.blockPosition()
	if (MinecraftPathingRules.isClimbable(level, pos) || MinecraftPathingRules.isClimbable(level, pos.above())) {
		return false
	}
	// If there isn't headroom, assume tunnel/overhang instead of a hole.
	if (!MinecraftPathingRules.isPassable(level, pos.above()) || !MinecraftPathingRules.isPassable(level, pos.above(2))) {
		return false
	}
	for (dir in Direction.Plane.HORIZONTAL) {
		val forward = pos.relative(dir)
		if (MinecraftPathingRules.isWalkable(level, forward)) return false
		if (MinecraftPathingRules.isWalkable(level, forward.above())) return false
		if (MinecraftPathingRules.isWalkable(level, forward.below())) return false
	}
	return true
}

private fun shouldSprint(player: net.minecraft.client.player.LocalPlayer, forward: Float): Boolean {
	if (forward <= 0.6f) {
		return false
	}
	if (!player.onGround()) {
		return false
	}
	if (player.isShiftKeyDown) {
		return false
	}
	return true
}

private fun shouldPrioritizeEscape(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	waypoint: BlockPos
): Boolean {
	if (!player.onGround()) {
		return false
	}
	if (waypoint.y - player.blockY < 1) {
		return false
	}
	val pos = player.blockPosition()
	var solidSides = 0
	for (dir in Direction.Plane.HORIZONTAL) {
		if (!MinecraftPathingRules.isPassable(level, pos.relative(dir))) {
			solidSides++
		}
	}
	if (solidSides < 3) {
		return false
	}
	return attemptEscapeHole(client, level, player, waypoint)
}

private fun handleBehindRepath(
	client: Minecraft,
	level: Level,
	player: net.minecraft.client.player.LocalPlayer,
	goal: BlockPos,
	dist2: Double
): Boolean {
	if (goal == target && isAtTarget(player, goal, FINAL_ARRIVE_DISTANCE)) {
		return false
	}
	if (dist2 <= WAYPOINT_DISTANCE * WAYPOINT_DISTANCE) {
		behindTicks = 0
		lastDist2 = dist2
		return false
	}
	val absDelta = kotlin.math.abs(debugDelta)
	if (absDelta < BEHIND_YAW_DEGREES) {
		behindTicks = 0
		lastDist2 = dist2
		return false
	}
	if (!lastDist2.isNaN() && dist2 <= lastDist2 - BEHIND_DIST_EPS) {
		behindTicks = 0
		lastDist2 = dist2
		return false
	}
	behindTicks++
	lastDist2 = dist2
	if (behindTicks >= BEHIND_TICKS_LIMIT) {
		behindTicks = 0
		DebugLog.status(client, "Path", "Stuck: target behind, repath.")
		return attemptRepath(client, level, player, "behind", goal, hard = false)
	}
	return false
}

private fun updateLastPos(player: net.minecraft.client.player.LocalPlayer) {
	lastPosX = player.x
	lastPosY = player.y
	lastPosZ = player.z
	hasLastPos = true
}

private fun resetStuck() {
	stuckTicks = 0
	hasLastPos = false
	pauseTicks = 0
	behindTicks = 0
	lastDist2 = Double.NaN
	belowTicks = 0
	smoothedTargetYaw = null
	smoothedTargetPitch = null
	lastRenderRotateNs = 0L
	desiredYaw = null
	desiredPitch = null
	blockedRepathUntilTick = 0L
	blockedRepathFails = 0
	allowBackwardTicks = 0
	waterEscapeTarget = null
	waterEscapeActive = false
	lastNavMeshTick = 0L
}

	private fun approach(current: Float, target: Float, accel: Float, decel: Float): Float {
		val rate = if (kotlin.math.abs(target) < 1.0e-4f) decel else accel
		return current + (target - current) * rate
	}

	private fun smoothRotate(player: net.minecraft.client.player.LocalPlayer, targetYaw: Float) {
		val baseTarget = targetYaw.toDouble()
		var basePitch = debugLookPitch
		if (kotlin.math.abs(debugDy) <= PITCH_FLATTEN_DY) {
			basePitch = 0.0
		} else {
			basePitch = basePitch.coerceIn(-PITCH_MAX_DEGREES, PITCH_MAX_DEGREES)
		}
		val now = System.nanoTime()
		val dt =
			if (lastRotateNs == 0L) {
				1.0 / 20.0
			} else {
				((now - lastRotateNs) / 1_000_000_000.0).coerceIn(1.0 / 240.0, 0.10)
			}
		lastRotateNs = now
		val alpha = 1.0 - exp(-dt * ROTATE_TARGET_RESPONSE)
		val smoothed = smoothedTargetYaw?.let { current ->
			current + wrapDegrees(baseTarget - current) * alpha
		} ?: baseTarget
		smoothedTargetYaw = smoothed
		val smoothedPitch = smoothedTargetPitch?.let { current ->
			current + (basePitch - current) * alpha
		} ?: basePitch
		smoothedTargetPitch = smoothedPitch
		desiredYaw = smoothed
		desiredPitch = smoothedPitch
	}

	private fun attemptRepath(
		client: Minecraft,
		level: Level,
		player: net.minecraft.client.player.LocalPlayer,
		reason: String,
		goal: BlockPos,
		hard: Boolean,
		allowSame: Boolean = true
	): Boolean {
		val forwardPos = player.blockPosition().relative(Direction.fromYRot(player.yRot.toDouble()))
		val forwardState = level.getBlockState(forwardPos)
		val forwardId = BuiltInRegistries.BLOCK.getKey(forwardState.block).toString()
		DebugLog.status(
			client,
			"Path",
			"Repath $reason: front=$forwardId @ ${forwardPos.x} ${forwardPos.y} ${forwardPos.z}"
		)
		setRepath(reason, level)
		val finalGoal = target ?: goal
		val repathStart = resolveRepathStart(level, player.blockPosition(), finalGoal)
		if (repathStart == null) {
			DebugLog.status(client, "Path", "Repath $reason: no valid start.")
			if (hard) {
				DebugLog.status(client, "Path", "Failed: repath ($reason) found no valid start.")
				stop(client, "Lost path.")
				return true
			}
			return false
		}
		val newPath = planPath(level, repathStart, finalGoal, resolveProfile(currentProfileId))
		if (newPath.isNotEmpty()) {
			val changed = !isSamePath(newPath)
			if (!changed && !allowSame) {
				DebugLog.status(client, "Path", "Repath $reason: no change.")
				return false
			}
			path = newPath.toMutableList()
			pathIndex = 0
			checkpoints = buildCheckpoints(path)
			checkpointIndex = 0
			smoothedTargetYaw = null
			if (reason.startsWith("stuck") || reason == "behind" || reason.startsWith("recover")) {
				allowBackwardTicks = BACKWARD_ALLOW_TICKS
			}
			recovering = false
			recoveryGoal = null
			recoveryTargetIndex = -1
			mainPath = path.toList()
			mainPathIndex = 0
			DebugLog.status(client, "Path", "Repath $reason: ok (nodes=${path.size}).")
			return true
		}
		DebugLog.status(client, "Path", "Repath $reason: no path.")
		if (hard) {
			DebugLog.status(client, "Path", "Failed: repath ($reason) found no path.")
			stop(client, "Lost path.")
			return true
		}
		return false
	}

	private fun resolveRepathStart(level: Level, origin: BlockPos, hintTarget: BlockPos): BlockPos? {
		MinecraftPathingRules.walkableAt(level, origin)?.let { return it }
		return findNavMeshAnchor(level, origin, hintTarget)
	}

	private fun isSamePath(newPath: List<BlockPos>): Boolean {
		if (path.isEmpty()) {
			return false
		}
		val count = minOf(4, path.size, newPath.size)
		for (i in 0 until count) {
			if (path[i] != newPath[i]) {
				return false
			}
		}
		return true
	}

	private fun writeDebug(client: Minecraft, player: net.minecraft.client.player.LocalPlayer) {
		if (!DEBUG_LOG && !DEBUG_CHAT && !DEBUG_TICK_CHAT && !DEBUG_TICK_FILE) {
			return
		}
		val level = player.level()
		if (!DEBUG_CHAT && !DEBUG_LOG && !DEBUG_TICK_CHAT && !DEBUG_TICK_FILE) {
			return
		}
		val finalTarget = target
		val distToTarget =
			if (finalTarget != null) {
				val dxT = (finalTarget.x + 0.5) - player.x
				val dyT = (finalTarget.y + 0.5) - player.y
				val dzT = (finalTarget.z + 0.5) - player.z
				sqrt(dxT * dxT + dyT * dyT + dzT * dzT)
			} else {
				0.0
			}
		val debugLine =
			"dbg f:${"%.2f".format(debugForward)} s:${"%.2f".format(debugStrafe)} j:${debugJump} dy:${debugDy} " +
			"dot:${"%.2f".format(debugDot)} flip:${debugFlip} yaw:${"%.1f".format(player.yRot)} dYaw:${"%.1f".format(debugDelta)} " +
			"tYaw:${"%.1f".format(debugTargetYaw)} tPitch:${"%.1f".format(debugTargetPitch)} " +
			"lYaw:${"%.1f".format(debugLookYaw)} lPitch:${"%.1f".format(debugLookPitch)} " +
			"sYaw:${"%.1f".format(debugSplineYaw)} cPitch:${"%.1f".format(debugCurrentPitch)} " +
			"yErr:${"%.1f".format(debugYawError)} pErr:${"%.1f".format(debugPitchError)} " +
			"pos:${"%.2f".format(player.x)},${"%.2f".format(player.y)},${"%.2f".format(player.z)} " +
			"blk:${player.blockX},${player.blockY},${player.blockZ} " +
			"block:${level.getBlockState(player.blockPosition()).block.descriptionId} " +
			"prog:${pathIndex + 1}/${max(path.size, 1)} rec:${recovering} dist:${"%.2f".format(distToTarget)} " +
			"target:${"%.2f".format(debugTargetX)},${"%.2f".format(debugTargetY)},${"%.2f".format(debugTargetZ)}"
		if (DEBUG_TICK_CHAT || DEBUG_TICK_FILE) {
			DebugLog.debugTick(client, "Path", debugLine, level.gameTime)
		}
		if (DEBUG_LOG) {
			val now = level.gameTime
			if (debugLogTick == 0L || now - debugLogTick >= DEBUG_LOG_INTERVAL_TICKS) {
				debugLogTick = now
				logger.info(
					"Path dbg node={}/{} cp={}/{} move f={} s={} sc={} jump={} dy={} dYaw={} dot={} flip={} stuck={} repath={} pos={},{},{} target={},{},{}",
					pathIndex + 1,
					max(path.size, 1),
					checkpointIndex + 1,
					max(checkpoints.size, 1),
					String.format("%.2f", debugForward),
					String.format("%.2f", debugStrafe),
					String.format("%.2f", debugStrafeScale),
					debugJump,
					debugDy,
					String.format("%.1f", debugDelta),
					String.format("%.3f", debugDot),
					debugFlip,
					stuckTicks,
					debugRepathReason,
					String.format("%.2f", player.x),
					String.format("%.2f", player.y),
					String.format("%.2f", player.z),
					String.format("%.2f", debugTargetX),
					String.format("%.2f", debugTargetY),
					String.format("%.2f", debugTargetZ)
				)
			}
		}
		if (DEBUG_CHAT) {
			DebugLog.debug(client, "Path", debugLine, true)
		}
	}

	private fun setRepath(reason: String, level: Level) {
		debugRepathReason = reason
		debugRepathTick = level.gameTime
	}

}

