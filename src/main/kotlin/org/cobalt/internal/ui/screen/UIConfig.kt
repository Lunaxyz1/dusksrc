package org.cobalt.internal.ui.screen

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.helper.Config
import org.cobalt.internal.ui.UIScreen
import org.cobalt.internal.ui.animation.BounceAnimation
import org.cobalt.internal.ui.components.tooltips.TooltipManager
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.panel.panels.UIAddonList
import org.cobalt.internal.ui.panel.panels.UISidebar

internal object UIConfig : UIScreen() {

  private val openAnim = BounceAnimation(400)
  private var wasClosed = true

  private val sidebar = UISidebar()
  private var body: UIPanel = UIAddonList()

  init {
    EventBus.register(this)
  }

  @Suppress("unused")
  @SubscribeEvent
  fun onRender(event: NvgEvent) {
    if (mc.screen != this)
      return

    val window = mc.window
    val width = window.screenWidth.toFloat()
    val height = window.screenHeight.toFloat()

    NVGRenderer.beginFrame(width, height)

    if (openAnim.isAnimating()) {
      val scale = openAnim.get(0f, 1f)
      val cx = width / 2f
      val cy = height / 2f

      NVGRenderer.translate(cx, cy)
      NVGRenderer.scale(scale, scale)
      NVGRenderer.translate(-cx, -cy)
    }

    val originX = width / 2f - 480f
    val originY = height / 2f - 300f

    sidebar
      .updateBounds(originX, originY)
      .render()

    body
      .updateBounds(originX + 80f, originY)
      .render()

    TooltipManager.renderAll()
    NVGRenderer.endFrame()
  }

  override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
    return body.mouseClicked(click.button()) ||
      sidebar.mouseClicked(click.button()) ||
      super.mouseClicked(click, doubled)
  }

  override fun mouseReleased(click: MouseButtonEvent): Boolean {
    return body.mouseReleased(click.button()) ||
      super.mouseReleased(click)
  }

  override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
    return body.mouseDragged(click.button(), offsetX, offsetY) ||
      super.mouseDragged(click, offsetX, offsetY)
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    return body.charTyped(input) ||
      super.charTyped(input)
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    return body.keyPressed(input) ||
      super.keyPressed(input)
  }

  override fun mouseScrolled(
    mouseX: Double,
    mouseY: Double,
    horizontalAmount: Double,
    verticalAmount: Double,
  ): Boolean {
    return body.mouseScrolled(horizontalAmount, verticalAmount) ||
      super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
  }

  override fun init() {
    if (wasClosed) {
      openAnim.start()
      wasClosed = false
    }

    super.init()
  }

  override fun onClose() {
    Config.saveModulesConfig()
    wasClosed = true
    super.onClose()
  }

  fun swapBodyPanel(panel: UIPanel): UIPanel {
    val previous = body
    this.body = panel
    return previous
  }

  fun getBodyPanel(): UIPanel {
    return body
  }

}
