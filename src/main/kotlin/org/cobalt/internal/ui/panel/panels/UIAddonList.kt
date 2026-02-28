package org.cobalt.internal.ui.panel.panels

import net.fabricmc.loader.api.FabricLoader
import org.cobalt.api.addon.Addon
import org.cobalt.api.addon.AddonMetadata
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.loader.AddonLoader
import org.cobalt.internal.mining.MiningModule
import org.cobalt.internal.mining.FairyModule
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.components.UIAddonEntry
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.util.GridLayout
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIAddonList : UIPanel(
  x = 0F,
  y = 0F,
  width = 890F,
  height = 600F
) {

  private val topBar = UITopbar("Addons")
  private val allEntries = buildAddonEntries()
  private var entries = allEntries

  private val gridLayout = GridLayout(
    columns = 3,
    itemWidth = 270F,
    itemHeight = 70F,
    gap = 20F
  )

  private val scrollHandler = ScrollHandler()

  init {
    components.addAll(allEntries)
    components.add(topBar)

    topBar.searchChanged { searchText ->
      entries = if (searchText.isEmpty()) {
        allEntries
      } else {
        allEntries.filter { it.metadata.name.lowercase().contains(searchText.lowercase()) }
      }
    }
  }

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.background, 10F)

    topBar
      .updateBounds(x, y)
      .render()

    val startY = y + topBar.height
    val visibleHeight = height - topBar.height

    scrollHandler.setMaxScroll(gridLayout.contentHeight(entries.size) + 20F, visibleHeight)
    NVGRenderer.pushScissor(x, startY, width, visibleHeight)

    val scrollOffset = scrollHandler.getOffset()
    gridLayout.layout(x + 20F, startY + 20F - scrollOffset, entries)
    entries.forEach(UIComponent::render)

    NVGRenderer.popScissor()
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (isHoveringOver(x, y, width, height)) {
      scrollHandler.handleScroll(verticalAmount)
      return true
    }

    return false
  }

  private fun buildAddonEntries(): List<UIAddonEntry> {
    val entries = mutableListOf<UIAddonEntry>()
    entries.addAll(AddonLoader.getAddons().map { UIAddonEntry(it.first, it.second) })

    val addonModules = AddonLoader.getAddons().flatMap { it.second.getModules() }.toSet()
    val builtinModules = ModuleManager.getModules().filter { it !in addonModules }
    val miningModules =
      builtinModules.filter {
        it == MiningModule ||
          it == FairyModule ||
          it.name.equals("Mining", ignoreCase = true) ||
          it.name.equals("Fairy", ignoreCase = true)
      }
    val remainingBuiltinModules = builtinModules.filter { it !in miningModules }

    val version = FabricLoader.getInstance()
      .getModContainer("cobalt")
      .map { it.metadata.version.friendlyString }
      .orElse("builtin")

    if (miningModules.isNotEmpty()) {
      val miningMetadata = AddonMetadata(
        id = "cobalt-mining",
        name = "Mining",
        version = version,
        entrypoints = emptyList(),
        mixins = emptyList()
      )

      val miningAddon = object : Addon() {
        override fun onLoad() {}

        override fun onUnload() {}

        override fun getModules() = miningModules
      }

      entries.add(0, UIAddonEntry(miningMetadata, miningAddon))
    }

    if (remainingBuiltinModules.isNotEmpty()) {
      val builtinMetadata = AddonMetadata(
        id = "cobalt",
        name = "Dutt Client",
        version = version,
        entrypoints = emptyList(),
        mixins = emptyList()
      )

      val builtinAddon = object : Addon() {
        override fun onLoad() {}

        override fun onUnload() {}

        override fun getModules() = remainingBuiltinModules
      }

      entries.add(0, UIAddonEntry(builtinMetadata, builtinAddon))
    }

    return entries
  }

}
