package org.cobalt.internal.ui.panel.panels

import net.minecraft.client.Minecraft
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.components.UIBackButton
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.util.GridLayout
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIRoutePointPicker(
  private val returnPanel: UIPanel,
  private val closeOnDone: Boolean
) : UIPanel(
  x = 0F,
  y = 0F,
  width = 890F,
  height = 600F
) {

  private val topBar = UITopbar("Route Point Type")
  private val backButton = UIBackButton { close() }
  private val buttons = listOf(
    RouteButton("Walk", "Standard route point.") { select(RoutesModule.RoutePointType.NORMAL) },
    RouteButton("Warp", "Warp point trigger.") { select(RoutesModule.RoutePointType.WARP) },
    RouteButton("Mine", "Mine vein start/end.") { select(RoutesModule.RoutePointType.MINE) },
  )

  private val gridLayout = GridLayout(
    columns = 1,
    itemWidth = 360F,
    itemHeight = 64F,
    gap = 14F
  )

  init {
    components.addAll(buttons)
    components.add(backButton)
    components.add(topBar)
  }

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.background, 10F)

    topBar
      .updateBounds(x, y)
      .render()

    backButton
      .updateBounds(x + 20F, y + topBar.height + 20F)
      .render()

    val startY = y + topBar.height + 80F
    gridLayout.layout(x + width / 2F - 180F, startY, buttons)
    buttons.forEach(UIComponent::render)
  }

  private fun select(type: RoutesModule.RoutePointType) {
    RoutesModule.applyPickedType(type)
    close()
  }

  private fun close() {
    UIConfig.swapBodyPanel(returnPanel)
    if (closeOnDone) {
      Minecraft.getInstance().setScreen(null)
    }
  }

  private class RouteButton(
    private val title: String,
    private val subtitle: String,
    private val onClick: () -> Unit
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = 360F,
    height = 64F
  ) {

    private val colorAnim = ColorAnimation(160L)
    private var wasHovering = false

    override fun render() {
      val hovering = isHoveringOver(x, y, width, height)
      if (hovering != wasHovering) {
        colorAnim.start()
        wasHovering = hovering
      }

      val bg = colorAnim.get(
        ThemeManager.currentTheme.panel,
        ThemeManager.currentTheme.selectedOverlay,
        !hovering
      )
      val border = colorAnim.get(
        ThemeManager.currentTheme.controlBorder,
        ThemeManager.currentTheme.accent,
        !hovering
      )
      val text = colorAnim.get(
        ThemeManager.currentTheme.text,
        ThemeManager.currentTheme.accent,
        !hovering
      )

      NVGRenderer.rect(x, y, width, height, bg, 10F)
      NVGRenderer.hollowRect(x, y, width, height, 1.5F, border, 10F)
      NVGRenderer.text(title, x + 18F, y + 16F, 16F, text)
      NVGRenderer.text(subtitle, x + 18F, y + 36F, 12F, ThemeManager.currentTheme.textSecondary)
    }

    override fun mouseClicked(button: Int): Boolean {
      if (button == 0 && isHoveringOver(x, y, width, height)) {
        onClick()
        return true
      }
      return false
    }
  }

  companion object {
    fun open() {
      val mc = Minecraft.getInstance()
      val wasOpen = mc.screen === UIConfig
      val previous = UIConfig.getBodyPanel()
      UIConfig.swapBodyPanel(UIRoutePointPicker(previous, !wasOpen))
      UIConfig.openUI()
    }
  }
}
