package org.cobalt.internal.pathfinding
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

object PathOverlayRenderer {
	private const val MARKER_RENDER_COUNT = 10
	private const val MARKER_INSET = 0.08
	private const val MARKER_HEIGHT = 0.08
	private const val FINAL_HEIGHT = 0.16

	fun updateOverlays(
		level: Level,
		player: net.minecraft.client.player.LocalPlayer,
		checkpoints: List<BlockPos>,
		checkpointIndex: Int,
		active: Boolean,
		pathTag: String
	) {
		OverlayRenderEngine.clearTag(pathTag)
		if (!active) {
			return
		}
		val startIndex = checkpointIndex.coerceAtLeast(0)
		val endIndex = minOf(checkpoints.size, startIndex + MARKER_RENDER_COUNT)
		if (startIndex >= endIndex) {
			return
		}

		val lineColor = OverlayRenderEngine.Color(0xB4, 0x63, 0xFF, 0xCC)
		val fillColor = OverlayRenderEngine.Color(0x7A, 0x3B, 0xC4, 0x55)
		val edgeColor = OverlayRenderEngine.Color(0xC2, 0x7B, 0xFF, 0xD0)
		val finalLineColor = OverlayRenderEngine.Color(0xFF, 0xB1, 0x4F, 0xD5)
		val finalFillColor = OverlayRenderEngine.Color(0xFF, 0x8E, 0x2E, 0x66)
		val finalEdgeColor = OverlayRenderEngine.Color(0xFF, 0xD1, 0x7A, 0xF0)

		var prevX = player.x
		var prevY = player.y + 0.05
		var prevZ = player.z

		for (i in startIndex until endIndex) {
			val pos = checkpoints[i]
			val centerX = pos.x + 0.5
			val centerY = pos.y + 0.02
			val centerZ = pos.z + 0.5
			val isFinal = i == checkpoints.lastIndex
			OverlayRenderEngine.addLine(
				level,
				prevX,
				prevY,
				prevZ,
				centerX,
				centerY,
				centerZ,
				if (isFinal) finalLineColor else lineColor,
				lineWidth = if (isFinal) 2.2f else 1.6f,
				durationTicks = 2,
				tag = pathTag
			)
			prevX = centerX
			prevY = centerY
			prevZ = centerZ

			val height = if (isFinal) FINAL_HEIGHT else MARKER_HEIGHT
			OverlayRenderEngine.addBox(
				level,
				pos.x + MARKER_INSET,
				pos.y + 0.02,
				pos.z + MARKER_INSET,
				pos.x + 1.0 - MARKER_INSET,
				pos.y + height,
				pos.z + 1.0 - MARKER_INSET,
				if (isFinal) finalFillColor else fillColor,
				if (isFinal) finalEdgeColor else edgeColor,
				lineWidth = if (isFinal) 2.2f else 1.6f,
				durationTicks = 2,
				tag = pathTag
			)
		}
	}
}
