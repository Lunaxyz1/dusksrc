package org.cobalt.internal.pathfinding

import kotlin.math.abs
import kotlin.math.tanh

object HeadRotationModule {
	private const val MAX_TURN_SPEED_PER_SEC = 120f
	private const val MAX_TURN_ACCEL_PER_SEC2 = 360f
	private const val TURN_HYPERBOLIC_SCALE = 90.0
	private const val MIN_DELTA_EPS = 1.0e-4f
	private const val MAX_DT_SEC = 0.10f
	private const val MIN_DT_SEC = 1f / 240f

	private var velocity = 0f
	private var lastTimeNs = 0L

	fun computeTurnDelta(
		yawDelta: Float,
		maxSpeedScale: Float = 1f,
		accelScale: Float = 1f
	): Float {
		val absDelta = abs(yawDelta)
		if (absDelta < MIN_DELTA_EPS) {
			velocity = 0f
			lastTimeNs = System.nanoTime()
			return 0f
		}

		val now = System.nanoTime()
		val dt =
			if (lastTimeNs == 0L) {
				1f / 20f
			} else {
				((now - lastTimeNs) / 1_000_000_000.0f).coerceIn(MIN_DT_SEC, MAX_DT_SEC)
			}
		lastTimeNs = now

		// Hyperbolic easing: near-zero deltas ease slowly, large deltas approach max speed.
		val speedPerSec =
			(MAX_TURN_SPEED_PER_SEC * tanh(absDelta / TURN_HYPERBOLIC_SCALE)).toFloat() *
				maxSpeedScale
		val desiredSpeed = if (yawDelta >= 0f) speedPerSec else -speedPerSec
		val maxAccel = MAX_TURN_ACCEL_PER_SEC2 * accelScale
		val speedDelta = desiredSpeed - velocity
		val maxDelta = maxAccel * dt
		velocity += speedDelta.coerceIn(-maxDelta, maxDelta)

		val turn = velocity * dt
		return if (absDelta < abs(turn)) {
			velocity = 0f
			yawDelta
		} else {
			turn
		}
	}
}
