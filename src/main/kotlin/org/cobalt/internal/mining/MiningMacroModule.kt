package org.cobalt.internal.mining

import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.BezierTrackingRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.DuskPathfinder
import org.cobalt.internal.pathfinding.PathfindingModule

object MiningMacroModule : Module("Mining Macro") {

  private val mc: Minecraft = Minecraft.getInstance()
  private val rotationStrategy = BezierTrackingRotationStrategy(14f, 10f)

  private val enabled = CheckboxSetting(
    "Enabled",
    "Automatically scan, move to, and mine veins.",
    false
  )

  private val info = InfoSetting(
    "Macro",
    "Scans for the closest vein and mines it until empty.",
    InfoType.INFO
  )

  private val useMiningModuleType = CheckboxSetting(
    "Use Mining Type",
    "Use the Mining module block type selection.",
    true
  )

  private val blockType = ModeSetting(
    "Block Type",
    "Type used when not synced from Mining module.",
    0,
    MiningBlockRegistry.BLOCK_TYPES
  )

  private val useDetectedBlock = CheckboxSetting(
    "Use Detected Block",
    "When type is Custom, use Mining module detected block id.",
    true
  )

  private val scanRadius = SliderSetting(
    "Scan Radius",
    "Horizontal scan radius for veins.",
    32.0,
    8.0,
    96.0
  )

  private val scanVertical = SliderSetting(
    "Scan Vertical",
    "Vertical scan range above/below you.",
    8.0,
    2.0,
    40.0
  )

  private val scanPerTick = SliderSetting(
    "Scan Per Tick",
    "Blocks scanned per tick when searching.",
    400.0,
    50.0,
    2000.0
  )

  private val maxVeinBlocks = SliderSetting(
    "Max Vein Blocks",
    "Maximum blocks collected per vein.",
    256.0,
    32.0,
    512.0
  )

  private val mineRange = SliderSetting(
    "Mine Range",
    "Distance to mine blocks.",
    4.5,
    2.0,
    6.0
  )

  private val requireMineLos = CheckboxSetting(
    "Require LOS",
    "Only mine blocks in line of sight.",
    true
  )

  private val useInstantTransmission = CheckboxSetting(
    "Instant Transmission",
    "Use right-click warp (no sneak) when possible.",
    true
  )

  private val warpMinDistance = SliderSetting(
    "Warp Min Dist",
    "Only warp if target is farther than this.",
    10.0,
    0.0,
    30.0
  )

  private val warpAimTolerance = SliderSetting(
    "Warp Aim Tol",
    "Yaw/pitch error before warping.",
    6.0,
    2.0,
    15.0
  )

  private val warpCooldownTicks = SliderSetting(
    "Warp Cooldown",
    "Ticks to wait between warp attempts.",
    24.0,
    6.0,
    80.0
  )

  private val usePathfinding = CheckboxSetting(
    "Use Pathfinding",
    "Walk to the target when warping is not possible.",
    true
  )

  private val skipOccupiedVeins = CheckboxSetting(
    "Skip Occupied Veins",
    "Ignore veins with other players nearby.",
    true
  )

  private val occupiedRadius = SliderSetting(
    "Occupied Radius",
    "Radius around a vein considered occupied.",
    6.0,
    1.0,
    16.0
  )

  init {
    addSetting(
      enabled,
      info,
      useMiningModuleType,
      blockType,
      useDetectedBlock,
      scanRadius,
      scanVertical,
      scanPerTick,
      maxVeinBlocks,
      mineRange,
      requireMineLos,
      useInstantTransmission,
      warpMinDistance,
      warpAimTolerance,
      warpCooldownTicks,
      usePathfinding,
      skipOccupiedVeins,
      occupiedRadius,
    )

    EventBus.register(this)
  }

  private data class Offset(val dx: Int, val dy: Int, val dz: Int, val distSq: Int)

  private data class Vein(
    val blocks: MutableSet<BlockPos>,
    val blockId: String,
    val bounds: AABB
  )

  private var scanOffsets: List<Offset> = emptyList()
  private var scanIndex = 0
  private var scanOrigin: BlockPos = BlockPos.ZERO
  private var scanActive = false
  private var scanRadiusCached = 0
  private var scanVerticalCached = 0

  private var currentVein: Vein? = null
  private var currentTarget: BlockPos? = null
  private var lastPathTarget: BlockPos? = null
  private var lastWarnTick = 0L

  private var warpStage = 0
  private var warpTarget: BlockPos? = null
  private var warpStageTicks = 0
  private var warpCooldownUntil = 0L
  private var warpRestoreSlot = -1

  private var miningActive = false
  private var startedPath = false

  private val skippedSeeds = HashSet<Long>()

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      stopMacro("Disabled.")
      return
    }

    val player = mc.player ?: return
    val level = mc.level ?: return

    if (usePathfinding.value) {
      PathfindingModule.ensureEnabledForAutomation("mining macro")
    }

    if (startedPath && !DuskPathfinder.isActive()) {
      startedPath = false
      lastPathTarget = null
    }

    if (mc.screen != null) {
      stopMiningKeys()
      RotationExecutor.stopRotating()
      return
    }

    if (warpStage > 0) {
      handleWarp(player, level)
      return
    }

    val targetIds = resolveTargetIds()
    if (targetIds.isEmpty()) {
      warnOnce("No target block ids for selected type.")
      stopMiningKeys()
      return
    }

    val vein = currentVein
    if (vein == null || vein.blocks.isEmpty()) {
      currentVein = null
      currentTarget = null
      startOrContinueScan(level, player, targetIds)
      return
    }

    pruneVein(level, vein, targetIds)
    if (vein.blocks.isEmpty()) {
      currentVein = null
      currentTarget = null
      return
    }

    val target = selectMineTarget(level, player, vein)
    if (target == null) {
      currentTarget = null
      if (usePathfinding.value) {
        val nearest = selectNearestBlock(player, vein.blocks)
        if (nearest != null) {
          moveToward(level, player, nearest)
        }
      }
      stopMiningKeys()
      RotationExecutor.stopRotating()
      return
    }

    currentTarget = target
    val distSq = distanceToBlockSq(player, target)
    val inRange = distSq <= mineRange.value * mineRange.value

    if (inRange && (!requireMineLos.value || hasLineOfSight(level, player, target))) {
      if (startedPath && DuskPathfinder.isActive()) {
        DuskPathfinder.stop(mc, "Mining.")
      }
      startedPath = false
      lastPathTarget = null
      startMining(target)
    } else {
      stopMiningKeys()
      RotationExecutor.stopRotating()
      if (useInstantTransmission.value && tryStartWarp(player, level, target)) {
        return
      }
      if (usePathfinding.value) {
        moveToward(level, player, target)
      }
    }
  }

  private fun resolveTargetIds(): Set<String> {
    val label = resolveSelectedType()
    val ids = MiningBlockRegistry.idsForType(label)
    if (ids.isNotEmpty()) {
      return ids
    }
    if (useDetectedBlock.value && label.equals("Custom", ignoreCase = true)) {
      val detected = MiningModule.detectedBlockText.value.trim()
      if (detected.contains(":")) {
        return setOf(detected)
      }
    }
    return emptySet()
  }

  private fun resolveSelectedType(): String {
    return if (useMiningModuleType.value) {
      MiningModule.blockType.options.getOrNull(MiningModule.blockType.value) ?: "Custom"
    } else {
      blockType.options.getOrNull(blockType.value) ?: "Custom"
    }
  }

  private fun startOrContinueScan(
    level: net.minecraft.world.level.Level,
    player: Player,
    targetIds: Set<String>
  ) {
    if (!scanActive || player.blockPosition().distSqr(scanOrigin) > 4.0) {
      scanOrigin = player.blockPosition()
      scanIndex = 0
      scanActive = true
    }

    val radius = scanRadius.value.toInt()
    val vertical = scanVertical.value.toInt()
    if (scanOffsets.isEmpty() || scanRadiusCached != radius || scanVerticalCached != vertical) {
      scanOffsets = buildOffsets(radius, vertical)
      scanRadiusCached = radius
      scanVerticalCached = vertical
      scanIndex = 0
    }

    val perTick = scanPerTick.value.toInt().coerceAtLeast(1)
    var processed = 0
    while (scanIndex < scanOffsets.size && processed < perTick) {
      val off = scanOffsets[scanIndex++]
      processed++
      val pos = scanOrigin.offset(off.dx, off.dy, off.dz)
      val id = blockIdAt(level, pos)
      if (!targetIds.contains(id)) continue
      if (skippedSeeds.contains(pos.asLong())) continue

      val vein = buildVein(level, pos, id, maxVeinBlocks.value.toInt())
      if (skipOccupiedVeins.value && isVeinOccupied(level, vein, player)) {
        skippedSeeds.add(pos.asLong())
        continue
      }
      currentVein = vein
      scanActive = false
      ChatUtils.sendMessage("Mining macro: found vein with ${vein.blocks.size} blocks.")
      return
    }

    if (scanIndex >= scanOffsets.size) {
      scanActive = false
      warnOnce("No veins found in scan radius.")
    }
  }

  private fun buildOffsets(radius: Int, vertical: Int): List<Offset> {
    val list = ArrayList<Offset>()
    val rSq = radius * radius
    for (dy in -vertical..vertical) {
      for (dx in -radius..radius) {
        for (dz in -radius..radius) {
          val horizSq = dx * dx + dz * dz
          if (horizSq > rSq) continue
          val distSq = horizSq + dy * dy
          list.add(Offset(dx, dy, dz, distSq))
        }
      }
    }
    list.sortWith(compareBy({ it.distSq }, { it.dy }))
    return list
  }

  private fun buildVein(
    level: net.minecraft.world.level.Level,
    seed: BlockPos,
    blockId: String,
    maxBlocks: Int
  ): Vein {
    val blocks = LinkedHashSet<BlockPos>()
    val queue = ArrayDeque<BlockPos>()
    queue.add(seed)
    blocks.add(seed)

    var minX = seed.x
    var minY = seed.y
    var minZ = seed.z
    var maxX = seed.x
    var maxY = seed.y
    var maxZ = seed.z

    while (queue.isNotEmpty() && blocks.size < maxBlocks) {
      val pos = queue.removeFirst()
      for (dir in Direction.values()) {
        val next = pos.relative(dir)
        if (blocks.contains(next)) continue
        if (blockIdAt(level, next) != blockId) continue
        blocks.add(next)
        queue.add(next)
        if (next.x < minX) minX = next.x
        if (next.y < minY) minY = next.y
        if (next.z < minZ) minZ = next.z
        if (next.x > maxX) maxX = next.x
        if (next.y > maxY) maxY = next.y
        if (next.z > maxZ) maxZ = next.z
        if (blocks.size >= maxBlocks) break
      }
    }

    val bounds = AABB(
      minX.toDouble(),
      minY.toDouble(),
      minZ.toDouble(),
      maxX + 1.0,
      maxY + 1.0,
      maxZ + 1.0
    )
    return Vein(blocks, blockId, bounds)
  }

  private fun pruneVein(
    level: net.minecraft.world.level.Level,
    vein: Vein,
    targetIds: Set<String>
  ) {
    val iterator = vein.blocks.iterator()
    while (iterator.hasNext()) {
      val pos = iterator.next()
      if (!targetIds.contains(blockIdAt(level, pos))) {
        iterator.remove()
      }
    }
  }

  private fun selectMineTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    vein: Vein
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (pos in vein.blocks) {
      if (requireMineLos.value && !hasLineOfSight(level, player, pos)) continue
      val distSq = distanceToBlockSq(player, pos)
      if (distSq < bestDist) {
        bestDist = distSq
        best = pos
      }
    }
    return best ?: selectNearestBlock(player, vein.blocks)
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

  private fun moveToward(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ) {
    PathfindingModule.ensureEnabledForAutomation("mining macro")
    val approach = findApproach(level, player, target) ?: return
    if (!DuskPathfinder.isActive() || lastPathTarget == null || lastPathTarget?.distSqr(approach) ?: 0.0 > 1.0) {
      val started = DuskPathfinder.start(mc, approach)
      if (started) {
        startedPath = true
        lastPathTarget = approach
      } else if (!DuskPathfinder.isActive()) {
        startedPath = false
        lastPathTarget = null
      }
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

  private fun tryStartWarp(
    player: Player,
    level: net.minecraft.world.level.Level,
    target: BlockPos
  ): Boolean {
    if (!useInstantTransmission.value) return false
    if (level.gameTime < warpCooldownUntil) return false
    if (player.isShiftKeyDown) return false
    if (!EtherwarpLogic.holdingEtherwarpItem()) return false
    if (!EtherwarpLogic.canEtherwarp()) return false

    val eye = player.eyePosition
    val targetCenter = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val distSq = eye.distanceToSqr(targetCenter)
    val minDistSq = warpMinDistance.value * warpMinDistance.value
    if (distSq < minDistSq) return false

    val range = EtherwarpLogic.getEtherwarpRange().toDouble()
    if (distSq > range * range) return false

    if (!hasLineOfSight(level, player, target)) return false
    if (!ensureEtherwarpHotbarSelected()) return false

    if (startedPath && DuskPathfinder.isActive()) {
      DuskPathfinder.stop(mc, "Warping.")
    }
    startedPath = false

    warpTarget = target
    warpStage = 1
    warpStageTicks = 0
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

    when (warpStage) {
      1 -> {
        val rotation = AngleUtils.getRotation(Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5))
        RotationExecutor.rotateTo(rotation, rotationStrategy)
        warpStageTicks++

        val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
        val pitchError = abs(rotation.pitch - player.xRot)
        val tol = warpAimTolerance.value
        if ((yawError <= tol && pitchError <= tol) || warpStageTicks >= 20) {
          warpStage = 2
          warpStageTicks = 0
        }
      }
      2 -> {
        val useKey = mc.options.keyUse
        if (useKey != null) {
          useKey.setDown(true)
          player.swing(net.minecraft.world.InteractionHand.MAIN_HAND)
        }
        warpStage = 3
        warpStageTicks = 0
      }
      3 -> {
        val useKey = mc.options.keyUse
        useKey?.setDown(false)
        warpCooldownUntil = level.gameTime + warpCooldownTicks.value.toLong()
        restoreEtherwarpSlot()
        resetWarp()
      }
    }
  }

  private fun resetWarp() {
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
    return BuiltInRegistries.BLOCK.getKey(state.block).toString()
  }

  private fun distanceToBlockSq(player: Player, pos: BlockPos): Double {
    val dx = (pos.x + 0.5) - player.x
    val dy = (pos.y + 0.5) - player.y
    val dz = (pos.z + 0.5) - player.z
    return dx * dx + dy * dy + dz * dz
  }

  private fun isVeinOccupied(
    level: net.minecraft.world.level.Level,
    vein: Vein,
    player: Player
  ): Boolean {
    val radius = occupiedRadius.value
    val bounds = vein.bounds
    val aabb = AABB(
      bounds.minX - radius,
      bounds.minY - radius,
      bounds.minZ - radius,
      bounds.maxX + radius,
      bounds.maxY + radius,
      bounds.maxZ + radius
    )
    for (other in level.players()) {
      if (other == player) continue
      if (other.isSpectator) continue
      if (aabb.intersects(other.boundingBox)) {
        return true
      }
    }
    return false
  }

  private fun warnOnce(message: String) {
    val level = mc.level ?: return
    if (level.gameTime - lastWarnTick < 60L) return
    lastWarnTick = level.gameTime
    ChatUtils.sendMessage(message)
  }

  private fun stopMacro(reason: String) {
    if (startedPath && DuskPathfinder.isActive()) {
      DuskPathfinder.stop(mc, reason)
    }
    startedPath = false
    stopMiningKeys()
    RotationExecutor.stopRotating()
    restoreEtherwarpSlot()
    resetWarp()
    currentVein = null
    currentTarget = null
    lastPathTarget = null
    scanActive = false
  }
}
