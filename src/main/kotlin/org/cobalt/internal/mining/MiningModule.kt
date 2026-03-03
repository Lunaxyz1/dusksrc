package org.cobalt.internal.mining

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.util.getLoreLines

object MiningModule : Module("Mining") {

  private val mc: Minecraft = Minecraft.getInstance()

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable mining stats tracking.",
    false
  )

  val autoHotmCapture = CheckboxSetting(
    "Auto HOTM",
    "Auto-scan Heart of the Mountain perks when /hotm is open.",
    true
  )

  val blockStrength = SliderSetting(
    "Block Strength",
    "Block strength used in the tick formula (bstr).",
    10.0,
    1.0,
    200.0
  )

  val blockType = ModeSetting(
    "Block Type",
    "Select a known block hardness to calculate ticks.",
    0,
    MiningBlockRegistry.BLOCK_TYPES
  )

  val autoDetectBlock = CheckboxSetting(
    "Auto Detect Block",
    "Auto-detect block type from the block you're mining.",
    true
  )

  val detectedBlockText = TextSetting(
    "Detected Block",
    "Last detected block type (from crosshair).",
    "Unknown"
  )

  val pingDelayMin = SliderSetting(
    "Ping Delay Min",
    "Minimum extra ticks added based on ping.",
    0.5,
    0.0,
    2.0
  )

  val pingDelayMax = SliderSetting(
    "Ping Delay Max",
    "Maximum extra ticks added based on ping.",
    2.0,
    0.5,
    4.0
  )

  val miningSpeedText = TextSetting(
    "Mining Speed",
    "Last captured mining speed from /stats.",
    "0"
  )

  val hotmMultiplierText = TextSetting(
    "HOTM Mult",
    "Derived HOTM multiplier (currently precision miner only).",
    "1.00"
  )

  val miningGems = CheckboxSetting(
    "Mining Gems",
    "Apply gem-only perks (Professional).",
    false
  )

  val precisionActive = CheckboxSetting(
    "Precision Active",
    "Precision Miner particles are active (+30% mining speed).",
    false
  )

  val speedBoostActive = CheckboxSetting(
    "Speed Boost Active",
    "Mining Speed Boost active (+250% mining speed).",
    false
  )

  val frontLoadedActive = CheckboxSetting(
    "Front Loaded Active",
    "Front Loaded active (+250 mining speed).",
    false
  )

  val skymallActive = CheckboxSetting(
    "Skymall Active",
    "Skymall active (+100 mining speed).",
    false
  )

  val miningUmberTungsten = CheckboxSetting(
    "Mining Umber/Tungsten",
    "Apply Strong Arm bonus only while mining Umber or Tungsten.",
    false
  )

  val pingText = TextSetting(
    "Ping",
    "Current ping (ms).",
    "0"
  )

  val lookTicksText = TextSetting(
    "Look Ticks",
    "Computed ticks to look at a block.",
    "0"
  )

  private val scrapeStats = ActionSetting(
    "Scrape Stats",
    "Open /stats and scrape Mining Speed from the Mining Stats icon.",
    "Scrape"
  ) {
    pendingStatsScrape = true
    pendingStatsTick = mc.level?.gameTime ?: 0L
    sendCommand("/stats")
  }

  private val scrapeHotm = ActionSetting(
    "Scrape HOTM",
    "Open /hotm and export Heart of the Mountain perks.",
    "Scrape"
  ) {
    pendingHotmScrape = true
    pendingHotmTick = mc.level?.gameTime ?: 0L
    sendCommand("/hotm")
  }

  private val openStats = ActionSetting(
    "Open /stats",
    "Open the stats menu.",
    "Open"
  ) {
    sendCommand("/stats")
  }

  private val openHotm = ActionSetting(
    "Open /hotm",
    "Open the Heart of the Mountain tree.",
    "Open"
  ) {
    sendCommand("/hotm")
  }

  private val exportHotm = ActionSetting(
    "Export HOTM",
    "Export the current Heart of the Mountain perks to a text file.",
    "Export"
  ) {
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen == null) {
      notify("Open /hotm first.")
      return@ActionSetting
    }
    val perks = parseHotmPerks(screen)
    if (perks.isEmpty()) {
      notify("No HOTM perks found on this screen.")
      return@ActionSetting
    }
    hotmPerks = perks
    exportCombinedStats()
    updateHotmMultiplier()
    notify("Exported HOTM perks (${perks.size}).")
  }

  private var miningSpeed: Double = 0.0
  private var hotmPerks: Map<String, Int> = emptyMap()
  private var miningStats: Map<String, String> = emptyMap()
  private var hotmMultiplier: Double = 1.0
  private var pendingStatsScrape = false
  private var pendingStatsTick = 0L
  private var pendingHotmScrape = false
  private var pendingHotmTick = 0L
  private var lastHotmSignature: String? = null
  private var lastHotmParseTick = 0L

  init {
    addSetting(
      enabled,
      autoHotmCapture,
      blockStrength,
      blockType,
      autoDetectBlock,
      detectedBlockText,
      pingDelayMin,
      pingDelayMax,
      miningSpeedText,
      hotmMultiplierText,
      miningGems,
      precisionActive,
      speedBoostActive,
      frontLoadedActive,
      skymallActive,
      miningUmberTungsten,
      pingText,
      lookTicksText,
      scrapeStats,
      scrapeHotm,
      exportHotm,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) return

    val screen = mc.screen
    val pingMs = getPingMs()
    pingText.value = pingMs.toString()

    if (screen is AbstractContainerScreen<*>) {
      if (pendingStatsScrape) {
        captureMiningSpeedFromStats(screen)
      }
      if (autoHotmCapture.value || pendingHotmScrape) {
        captureHotmPerks(screen)
      }
    }

    if (autoDetectBlock.value) {
      updateBlockDetection()
    }

    updateLookTicks(pingMs)
  }

  private fun sendCommand(command: String) {
    val player = mc.player ?: return
    val trimmed = command.trim().removePrefix("/")
    player.connection?.sendCommand(trimmed)
  }

  private fun captureMiningSpeedFromStats(screen: AbstractContainerScreen<*>) {
    val title = screen.title.string.lowercase(Locale.ROOT)
    if (!title.contains("stat") && !title.contains("profile")) {
      return
    }
    val now = mc.level?.gameTime ?: 0L
    if (now - pendingStatsTick > STATS_SCRAPE_TIMEOUT_TICKS) {
      pendingStatsScrape = false
      notify("Failed to scrape Mining Speed (timeout).")
      return
    }
    val slot = findMiningStatsSlot(screen) ?: return
    trySetHoveredSlot(screen, slot)
    val stack = slot.item
    val lines = getTooltipLines(stack)
    if (lines.isEmpty()) return
    val stats = parseMiningStats(lines)
    if (stats.isNotEmpty()) {
      miningStats = stats
      exportCombinedStats()
    }

    val value = parseMiningSpeed(lines)
    if (value != null) {
      miningSpeed = value
      miningSpeedText.value = formatNumber(value)
      pendingStatsScrape = false
      notify("Captured Mining Speed: ${formatNumber(value)}")
    }
  }

  private fun captureHotmPerks(screen: AbstractContainerScreen<*>) {
    val title = screen.title.string.lowercase(Locale.ROOT)
    if (!title.contains("heart") && !title.contains("hotm")) {
      return
    }
    if (pendingHotmScrape) {
      val now = mc.level?.gameTime ?: 0L
      if (now - pendingHotmTick > HOTM_SCRAPE_TIMEOUT_TICKS) {
        pendingHotmScrape = false
        notify("Failed to scrape HOTM perks (timeout).")
        return
      }
    }
    val signature = "${title}:${screen.menu.containerId}:${screen.menu.slots.size}"
    val now = mc.level?.gameTime ?: 0L
    if (signature == lastHotmSignature && now - lastHotmParseTick < 10L) {
      return
    }
    lastHotmSignature = signature
    lastHotmParseTick = now

    val perks = parseHotmPerks(screen)
    if (perks.isEmpty()) return

    hotmPerks = perks
    exportCombinedStats()
    updateHotmMultiplier()
    if (pendingHotmScrape) {
      pendingHotmScrape = false
      notify("Exported HOTM perks (${perks.size}).")
    }
  }

  private fun parseHotmPerks(screen: AbstractContainerScreen<*>): Map<String, Int> {
    val result = LinkedHashMap<String, Int>()
    for (slot in screen.menu.slots) {
      val stack = slot.item
      if (stack.isEmpty) continue
      val name = stripFormatting(stack.hoverName.string)
      if (name.isBlank()) continue

      val lore = getTooltipLines(stack)
      val level = parsePerkLevel(lore)
      if (level != null && level > 0) {
        result[name] = level
      }
    }
    return result
  }

  private fun parseMiningSpeed(lines: List<Component>): Double? {
    for (line in lines) {
      val raw = stripFormatting(line.string).trim()
      val statValue = parseMiningSpeedStatValue(raw)
      if (statValue != null) {
        return statValue
      }
    }
    return null
  }

  private fun parsePerkLevel(lines: List<Component>): Int? {
    for (line in lines) {
      val raw = stripFormatting(line.string)
      val levelMatch = LEVEL_PATTERN.matcher(raw)
      if (levelMatch.find()) {
        return levelMatch.group(1).toIntOrNull()
      }
      val romanMatch = ROMAN_PATTERN.matcher(raw)
      if (romanMatch.find()) {
        return romanToInt(romanMatch.group(1))
      }
    }
    return null
  }

  private fun updateHotmMultiplier() {
    val base = miningSpeed
    if (base <= 0.0) {
      hotmMultiplier = 1.0
      hotmMultiplierText.value = "1.00"
      return
    }
    val effective = computeEffectiveMiningSpeed(base)
    val mult = effective / base
    hotmMultiplier = mult
    hotmMultiplierText.value = String.format(Locale.US, "%.2f", mult)
  }

  private fun updateLookTicks(pingMs: Int) {
    val speed = miningSpeed
    if (speed <= 0.0) {
      lookTicksText.value = "0"
      return
    }
    val bstr = resolveBlockStrength()
    val baseTicks = 30.0 * bstr / speed
    val delay = computePingDelayTicks(pingMs)
    val total = baseTicks + delay
    lookTicksText.value = String.format(Locale.US, "%.2f", total)
  }

  private fun computePingDelayTicks(pingMs: Int): Double {
    val min = pingDelayMin.value
    val max = pingDelayMax.value.coerceAtLeast(min)
    val normalized = (pingMs / 400.0).coerceIn(0.0, 1.0)
    return min + (max - min) * normalized
  }

  private fun getPingMs(): Int {
    val player = mc.player ?: return 0
    val info = player.connection?.getPlayerInfo(player.uuid)
    return info?.latency ?: 0
  }

  private fun exportCombinedStats() {
    val dir = mc.gameDirectory ?: return
    val outDir = File(dir, "config/cobalt")
    if (!outDir.exists()) {
      outDir.mkdirs()
    }
    val file = File(outDir, "hotm-perks.txt")
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .format(Instant.now())

    file.bufferedWriter().use { writer ->
      writer.appendLine("# HOTM + Mining Stats Export @ $stamp")
      writer.appendLine("")
      writer.appendLine("[HOTM Perks]")
      if (hotmPerks.isEmpty()) {
        writer.appendLine("None")
      } else {
        hotmPerks.forEach { (name, level) ->
          writer.appendLine("$name: $level")
        }
      }
      writer.appendLine("")
      writer.appendLine("[Mining Stats]")
      if (miningStats.isEmpty() && miningSpeed <= 0.0) {
        writer.appendLine("None")
      } else {
        val merged = LinkedHashMap<String, String>(miningStats)
        if (miningSpeed > 0.0 && !merged.keys.any { it.equals("Mining Speed", ignoreCase = true) }) {
          merged["Mining Speed"] = formatNumber(miningSpeed)
        }
        merged.forEach { (name, value) ->
          writer.appendLine("$name: $value")
        }
      }
      writer.appendLine("")
      writer.appendLine("[Derived Mining Speed]")
      val derived = computeDerivedMiningSpeed()
      writer.appendLine("Base Speed: ${formatNumber(derived.baseSpeed)}")
      writer.appendLine("HOTM Passive: +${formatNumber(derived.passiveBonus)}")
      writer.appendLine("Strong Arm: +${formatNumber(derived.strongArmBonus)} (active=${miningUmberTungsten.value})")
      writer.appendLine("Gem Bonus (Professional): +${formatNumber(derived.professionalBonus)}")
      writer.appendLine("Front Loaded: +${formatNumber(derived.frontLoadedBonus)} (active=${frontLoadedActive.value})")
      writer.appendLine("Skymall: +${formatNumber(derived.skymallBonus)} (active=${skymallActive.value})")
      writer.appendLine("Precision Miner: x${String.format(Locale.US, "%.2f", derived.precisionMultiplier)} (active=${precisionActive.value})")
      writer.appendLine("Speed Boost: +${formatNumber(derived.speedBoostBonus)} (active=${speedBoostActive.value})")
      writer.appendLine("Effective Speed: ${formatNumber(derived.effectiveSpeed)}")
      val blockLabel = blockType.options.getOrNull(blockType.value) ?: "Custom"
      writer.appendLine("Block Type: $blockLabel")
      writer.appendLine("Block Strength: ${formatNumber(resolveBlockStrength())}")
      writer.appendLine("")
      writer.appendLine("[Warm Heart]")
      writer.appendLine("Warm Heart Level: ${derived.warmHeartLevel}")
      writer.appendLine("Cold Reduction: ${String.format(Locale.US, "%.2f", derived.warmHeartCold)}")
    }
  }

  private fun findMiningStatsSlot(screen: AbstractContainerScreen<*>): Slot? {
    for (slot in screen.menu.slots) {
      val stack = slot.item
      if (stack.isEmpty) continue
      val name = stripFormatting(stack.hoverName.string).lowercase(Locale.ROOT)
      if (name.contains("mining stats")) {
        return slot
      }
      val lore = getTooltipLines(stack)
      for (line in lore) {
        val raw = stripFormatting(line.string).trim()
        if (parseMiningSpeedStatValue(raw) != null) {
          return slot
        }
      }
    }
    return null
  }

  private fun trySetHoveredSlot(screen: AbstractContainerScreen<*>, slot: Slot) {
    val field = hoveredSlotField ?: resolveHoveredSlotField(screen).also { hoveredSlotField = it }
    if (field != null) {
      try {
        field.set(screen, slot)
      } catch (_: Exception) {
        // ignore
      }
    }
  }

  private fun resolveHoveredSlotField(screen: AbstractContainerScreen<*>): java.lang.reflect.Field? {
    val cls = screen.javaClass
    val byName = cls.declaredFields.firstOrNull {
      Slot::class.java.isAssignableFrom(it.type) && it.name.contains("hover", ignoreCase = true)
    }
    if (byName != null) {
      byName.isAccessible = true
      return byName
    }
    val byType = cls.declaredFields.firstOrNull { Slot::class.java.isAssignableFrom(it.type) }
    if (byType != null) {
      byType.isAccessible = true
      return byType
    }
    return null
  }

  private fun extractNumber(text: String): Double? {
    val match = NUMBER_PATTERN.matcher(text)
    if (!match.find()) return null
    val raw = match.group(1).replace(",", "")
    return raw.toDoubleOrNull()
  }

  private fun parseMiningStats(lines: List<Component>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (line in lines) {
      val raw = stripFormatting(line.string).trim()
      if (raw.isEmpty()) continue
      val match = STAT_PAIR_PATTERN.matcher(raw)
      if (match.find()) {
        val name = match.group(1).trim()
        val value = match.group(2).trim()
        if (name.isNotBlank() && value.isNotBlank()) {
          result[name] = value
          continue
        }
      }
      val statValue = parseMiningSpeedStatValue(raw)
      if (statValue != null) {
        result["Mining Speed"] = formatNumber(statValue)
      }
    }
    return result
  }

  private fun parseMiningSpeedStatValue(raw: String): Double? {
    val match = STAT_PAIR_PATTERN.matcher(raw)
    if (!match.find()) return null
    val name = normalizeStatName(match.group(1))
    if (!name.equals("Mining Speed", ignoreCase = true)) return null
    return match.group(2).replace(",", "").toDoubleOrNull()
  }

  private fun normalizeStatName(raw: String): String {
    val stripped = raw.trim()
    val cleaned = LEADING_DECORATION_PATTERN.matcher(stripped).replaceAll("")
    return cleaned.trim()
  }

  private fun updateBlockDetection() {
    val level = mc.level ?: return
    val hit = mc.hitResult
    if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) {
      detectedBlockText.value = "Unknown"
      return
    }
    val state = level.getBlockState(hit.blockPos)
    val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
    val label = MiningBlockRegistry.BLOCK_ID_TO_TYPE[id]
    if (label != null) {
      detectedBlockText.value = label
      setBlockType(label)
    } else {
      detectedBlockText.value = id
    }
  }

  private fun setBlockType(label: String) {
    val idx = blockType.options.indexOf(label)
    if (idx >= 0 && blockType.value != idx) {
      blockType.value = idx
    }
  }

  private fun resolveBlockStrength(): Double {
    val selected = blockType.options.getOrNull(blockType.value) ?: "Custom"
    val hardness = MiningBlockRegistry.BLOCK_HARDNESS[selected]
    return hardness ?: blockStrength.value
  }

  private fun computeEffectiveMiningSpeed(baseSpeed: Double): Double {
    if (baseSpeed <= 0.0) return 0.0
    val derived = computeDerivedMiningSpeed(baseSpeed)
    return derived.effectiveSpeed
  }

  private fun computeDerivedMiningSpeed(baseOverride: Double? = null): DerivedMiningSpeed {
    val base = baseOverride ?: miningSpeed
    val perkMiningSpeed = getPerkLevel("mining speed") * 20.0
    val speedyMineman = getPerkLevel("speedy mineman") * 40.0
    val strongArmLevel = getPerkLevel("strong arm")
    val strongArm = if (miningUmberTungsten.value) strongArmLevel * 5.0 else 0.0
    val passive = perkMiningSpeed + speedyMineman + strongArm

    val professionalLevel = getPerkLevel("professional")
    val professionalBonus =
      if (miningGems.value && professionalLevel > 0) {
        55.0 + (professionalLevel - 1).coerceAtLeast(0) * 5.0
      } else {
        0.0
      }

    val frontLoadedBonus = if (frontLoadedActive.value) 250.0 else 0.0
    val skymallBonus = if (skymallActive.value) 100.0 else 0.0

    val baseTotal = base + passive + professionalBonus + frontLoadedBonus + skymallBonus

    val precisionLevel = getPerkLevel("precision miner")
    val precisionMultiplier =
      if (precisionActive.value && precisionLevel > 0) {
        1.0 + 0.3 * precisionLevel
      } else {
        1.0
      }
    val afterPrecision = baseTotal * precisionMultiplier

    val speedBoostBonus =
      if (speedBoostActive.value && getPerkLevel("mining speed boost") > 0) {
        afterPrecision * 2.5
      } else {
        0.0
      }

    val warmHeartLevel = getPerkLevel("warm heart")
    val warmHeartCold = warmHeartLevel * 0.4

    val effective = afterPrecision + speedBoostBonus
    return DerivedMiningSpeed(
      baseSpeed = base,
      passiveBonus = passive,
      strongArmBonus = strongArm,
      professionalBonus = professionalBonus,
      frontLoadedBonus = frontLoadedBonus,
      skymallBonus = skymallBonus,
      precisionMultiplier = precisionMultiplier,
      speedBoostBonus = speedBoostBonus,
      effectiveSpeed = effective,
      warmHeartLevel = warmHeartLevel,
      warmHeartCold = warmHeartCold
    )
  }

  private fun getPerkLevel(nameContains: String): Int {
    val target = nameContains.lowercase(Locale.ROOT)
    for ((name, level) in hotmPerks) {
      if (name.lowercase(Locale.ROOT).contains(target)) {
        return level
      }
    }
    return 0
  }

  private fun stripFormatting(text: String): String {
    return ChatFormatting.stripFormatting(text) ?: text
  }

  private fun formatNumber(value: Double): String {
    return if (value % 1.0 == 0.0) {
      value.toInt().toString()
    } else {
      String.format(Locale.US, "%.2f", value)
    }
  }

  private fun getTooltipLines(stack: ItemStack): List<Component> {
    val player = mc.player
    val level = mc.level
    return try {
      val methods = stack.javaClass.methods.filter { it.name == "getTooltipLines" }
      for (method in methods) {
        val params = method.parameterTypes
        if (params.size == 2 && TooltipFlag::class.java.isAssignableFrom(params[1])) {
          return method.invoke(stack, player, TooltipFlag.NORMAL) as? List<Component> ?: emptyList()
        }
        if (params.size == 3 && TooltipFlag::class.java.isAssignableFrom(params[2])) {
          val ctxParam = params[0]
          val ctx =
            when {
              level != null && ctxParam.isAssignableFrom(level.javaClass) -> level
              else -> buildTooltipContext(ctxParam, level)
            }
          return method.invoke(stack, ctx, player, TooltipFlag.NORMAL) as? List<Component> ?: emptyList()
        }
      }
      stack.getLoreLines()
    } catch (_: Exception) {
      stack.getLoreLines()
    }
  }

  private fun buildTooltipContext(ctxClass: Class<*>, level: net.minecraft.world.level.Level?): Any? {
    if (level != null) {
      val ofMethod = ctxClass.methods.firstOrNull { it.name == "of" && it.parameterTypes.size == 1 }
      if (ofMethod != null) {
        return try {
          ofMethod.invoke(null, level)
        } catch (_: Exception) {
          null
        }
      }
    }
    val emptyMethod = ctxClass.methods.firstOrNull { it.name == "empty" && it.parameterTypes.isEmpty() }
    if (emptyMethod != null) {
      return try {
        emptyMethod.invoke(null)
      } catch (_: Exception) {
        null
      }
    }
    return null
  }

  private fun notify(message: String) {
    NotificationManager.queue("Mining", message, 2000L)
  }

  private val NUMBER_PATTERN = Pattern.compile("([0-9][0-9,]*)")
  private val LEVEL_PATTERN = Pattern.compile("Level\\s+([0-9]+)")
  private val ROMAN_PATTERN = Pattern.compile("(?:Tier|Level)\\s+([IVX]+)", Pattern.CASE_INSENSITIVE)
  private val STAT_PAIR_PATTERN = Pattern.compile("^(.+?)\\s*[:\\s]+([0-9][0-9,]*(?:\\.[0-9]+)?)$")
  private val LEADING_DECORATION_PATTERN = Pattern.compile("^[^A-Za-z0-9]+")

  private fun romanToInt(roman: String): Int {
    var sum = 0
    var last = 0
    val chars = roman.uppercase(Locale.ROOT)
    for (i in chars.length - 1 downTo 0) {
      val value = when (chars[i]) {
        'I' -> 1
        'V' -> 5
        'X' -> 10
        else -> 0
      }
      if (value < last) sum -= value else sum += value
      last = value
    }
    return sum
  }

  private const val STATS_SCRAPE_TIMEOUT_TICKS = 60L
  private const val HOTM_SCRAPE_TIMEOUT_TICKS = 100L

  private var hoveredSlotField: java.lang.reflect.Field? = null

  private data class DerivedMiningSpeed(
    val baseSpeed: Double,
    val passiveBonus: Double,
    val strongArmBonus: Double,
    val professionalBonus: Double,
    val frontLoadedBonus: Double,
    val skymallBonus: Double,
    val precisionMultiplier: Double,
    val speedBoostBonus: Double,
    val effectiveSpeed: Double,
    val warmHeartLevel: Int,
    val warmHeartCold: Double
  )
}
