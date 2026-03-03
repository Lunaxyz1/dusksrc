package org.cobalt.internal.ui.hud

import java.awt.Color
import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.player.MovementManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.cobalt.internal.ui.util.mouseY

internal object RoutePointPopup {

  private val mc = Minecraft.getInstance()
  private var visible = false

  private val panelWidth = 280f
  private val panelHeight = 180f
  private val padding = 16f
  private val cornerRadius = 10f

  private var panelX = 0f
  private var panelY = 0f

  private val buttons = listOf(
    PopupButton("Walk", "Standard route point.") { select(RoutesModule.RoutePointType.NORMAL) },
    PopupButton("Warp", "Warp point trigger.") { select(RoutesModule.RoutePointType.WARP) },
    PopupButton("Mine", "Mine vein start/end.") { select(RoutesModule.RoutePointType.MINE) },
    PopupButton("Cancel", "Close without adding.") { close(true) },
  )

  fun open() {
    visible = true
    lockPlayer(true)
    MouseUtils.ungrabMouse()
  }

  fun isVisible(): Boolean = visible

  private fun close(cancel: Boolean) {
    visible = false
    if (cancel) {
      RoutesModule.cancelPendingPick()
    }
    lockPlayer(false)
    MouseUtils.grabMouse()
  }

  private fun select(type: RoutesModule.RoutePointType) {
    RoutesModule.applyPickedType(type)
    close(false)
  }

  @SubscribeEvent
  fun onRender(@Suppress("UNUSED_PARAMETER") event: NvgEvent) {
    if (!visible) return
    if (mc.screen != null) return

    val window = mc.window
    val width = window.screenWidth.toFloat()
    val height = window.screenHeight.toFloat()
    panelX = width / 2f - panelWidth / 2f
    panelY = height / 2f - panelHeight / 2f

    NVGRenderer.beginFrame(width, height)
    NVGRenderer.rect(0f, 0f, width, height, Color(0, 0, 0, 120).rgb)
    NVGRenderer.rect(panelX, panelY, panelWidth, panelHeight, ThemeManager.currentTheme.background, cornerRadius)
    NVGRenderer.hollowRect(panelX, panelY, panelWidth, panelHeight, 1f, ThemeManager.currentTheme.controlBorder, cornerRadius)

    NVGRenderer.text(
      "Route Point Type",
      panelX + padding,
      panelY + 14f,
      14f,
      ThemeManager.currentTheme.accent
    )

    val buttonWidth = panelWidth - padding * 2f
    val buttonHeight = 28f
    val gap = 8f
    var y = panelY + 46f
    buttons.forEach { button ->
      button.render(panelX + padding, y, buttonWidth, buttonHeight)
      y += buttonHeight + gap
    }

    NVGRenderer.endFrame()
  }

  @SubscribeEvent
  fun onMouse(event: MouseEvent.LeftClick) {
    if (!visible) return
    event.setCancelled(true)
    val mx = mouseX.toFloat()
    val my = mouseY.toFloat()

    val buttonWidth = panelWidth - padding * 2f
    val buttonHeight = 28f
    val gap = 8f
    var y = panelY + 46f
    for (button in buttons) {
      if (mx >= panelX + padding && mx <= panelX + padding + buttonWidth &&
        my >= y && my <= y + buttonHeight
      ) {
        button.onClick()
        return
      }
      y += buttonHeight + gap
    }

    if (!isHoveringOver(panelX, panelY, panelWidth, panelHeight)) {
      close(true)
    }
  }

  @SubscribeEvent
  fun onMouseRight(event: MouseEvent.RightClick) {
    if (!visible) return
    event.setCancelled(true)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!visible) return
    lockPlayer(true)
  }

  private fun lockPlayer(state: Boolean) {
    MovementManager.setLookLock(state)
    MovementManager.setMovementLock(state)
    if (!state) return
    val options = mc.options
    options.keyUp.setDown(false)
    options.keyDown.setDown(false)
    options.keyLeft.setDown(false)
    options.keyRight.setDown(false)
    options.keyJump.setDown(false)
    options.keyShift.setDown(false)
    options.keySprint.setDown(false)
    options.keyAttack.setDown(false)
    options.keyUse.setDown(false)
  }

  private class PopupButton(
    private val title: String,
    private val subtitle: String,
    val onClick: () -> Unit
  ) {
    private val anim = ColorAnimation(150L)
    private var wasHovering = false

    fun render(x: Float, y: Float, width: Float, height: Float) {
      val hovering = isHoveringOver(x, y, width, height)
      if (hovering != wasHovering) {
        anim.start()
        wasHovering = hovering
      }
      val bg = anim.get(ThemeManager.currentTheme.controlBg, ThemeManager.currentTheme.selectedOverlay, !hovering)
      val border = anim.get(ThemeManager.currentTheme.controlBorder, ThemeManager.currentTheme.accent, !hovering)
      val text = anim.get(ThemeManager.currentTheme.text, ThemeManager.currentTheme.accent, !hovering)
      NVGRenderer.rect(x, y, width, height, bg, 8f)
      NVGRenderer.hollowRect(x, y, width, height, 1f, border, 8f)
      NVGRenderer.text(title, x + 10f, y + 6f, 12f, text)
      NVGRenderer.text(subtitle, x + 10f, y + 17f, 10f, ThemeManager.currentTheme.textSecondary)
    }
  }
}
