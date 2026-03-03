package org.cobalt.api.hud.modules

import com.mojang.blaze3d.opengl.GlStateManager
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.GuiRenderEvent
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient

class InventoryHudModule : Module("Inventory HUD") {

  private val mc: Minecraft = Minecraft.getInstance()
  private lateinit var hudRef: org.cobalt.api.hud.HudElement
  private var poseMethod: java.lang.reflect.Method? = null
  private var poseField: java.lang.reflect.Field? = null
  private var posePushMethod: java.lang.reflect.Method? = null
  private var posePopMethod: java.lang.reflect.Method? = null
  private var poseTranslateMethod: java.lang.reflect.Method? = null
  private var poseScaleMethod: java.lang.reflect.Method? = null
  private var flushMethod: java.lang.reflect.Method? = null

  private val slotSize = 20f
  private val slotGap = 4f
  private val padding = 8f
  private val borderRadius = 9f
  private val slotRadius = 5f
  private val borderThickness = 1.5f
  private val baseScale = 1.46f
  private val itemNudgeX = 3f
  private val itemNudgeY = -0.1f

  private val outlineStart = 0xFF2DE2FF.toInt()
  private val outlineEnd = 0xFFFF6ACD.toInt()
  private val panelColor = 0x50101010.toInt()
  private val slotColor = 0x801A1A1A.toInt()

  val inventoryHud = hudElement(
    "inventory-hud",
    "Inventory HUD",
    "Displays your inventory items"
  ) {
    minScale = 1.0f
    anchor = HudAnchor.BOTTOM_CENTER
    offsetX = 0f
    offsetY = 24f

    val background = setting(CheckboxSetting("Background", "Show panel background", true))

    width {
      val rows = 3
      val cols = 9
      val uiSlotSize = slotSize * baseScale
      val uiSlotGap = slotGap * baseScale
      val uiPadding = padding * baseScale
      uiPadding * 2 + cols * uiSlotSize + (cols - 1) * uiSlotGap
    }

    height {
      val rows = 3
      val uiSlotSize = slotSize * baseScale
      val uiSlotGap = slotGap * baseScale
      val uiPadding = padding * baseScale
      uiPadding * 2 + rows * uiSlotSize + (rows - 1) * uiSlotGap
    }

    render { _, _, scale ->
      val rows = 3
      val cols = 9
      val uiSlotSize = slotSize * baseScale
      val uiSlotGap = slotGap * baseScale
      val uiPadding = padding * baseScale
      val uiBorderRadius = borderRadius * baseScale
      val uiSlotRadius = slotRadius * baseScale
      val uiBorderThickness = borderThickness * baseScale
      val width = uiPadding * 2 + cols * uiSlotSize + (cols - 1) * uiSlotGap
      val height = uiPadding * 2 + rows * uiSlotSize + (rows - 1) * uiSlotGap

      if (background.value) {
        NVGRenderer.rect(0f, 0f, width, height, panelColor, uiBorderRadius)
      }
      val angle = (System.currentTimeMillis() % 12000L).toFloat() / 12000f * (Math.PI * 2.0).toFloat()
      val shiftX = kotlin.math.cos(angle) * (width * 0.45f)
      val shiftY = kotlin.math.sin(angle) * (height * 0.45f)
      NVGRenderer.hollowGradientRectShifted(
        0f,
        0f,
        width,
        height,
        uiBorderThickness,
        outlineStart,
        outlineEnd,
        Gradient.LeftToRight,
        uiBorderRadius,
        shiftX,
        shiftY
      )

      for (row in 0 until rows) {
        for (col in 0 until cols) {
          val slotX = uiPadding + col * (uiSlotSize + uiSlotGap)
          val slotY = uiPadding + row * (uiSlotSize + uiSlotGap)
          NVGRenderer.rect(slotX, slotY, uiSlotSize, uiSlotSize, slotColor, uiSlotRadius)
        }
      }

    }

    postRender { _, _, scale ->
      // Items are rendered in GuiRenderEvent for correct GUI render state.
    }
  }

  init { hudRef = inventoryHud
    EventBus.register(this)}

  @SubscribeEvent
  fun onGuiRender(event: GuiRenderEvent) {
    if (!inventoryHud.enabled) return
    if (mc.screen != null && !HudModuleManager.isEditorOpen) return
    val player = mc.player ?: return
    val inventory = player.inventory
    val rows = 3
    val cols = 9
    val window = mc.window
    val (sx, sy) = hudRef.getScreenPosition(window.screenWidth.toFloat(), window.screenHeight.toFloat())
    val itemSize = 16f
    val itemOffset = (slotSize - itemSize) / 2f
    val guiScaledW = window.guiScaledWidth.toFloat()
    val guiScaledH = window.guiScaledHeight.toFloat()
    if (guiScaledW <= 0f || guiScaledH <= 0f) return
    val guiScaleX = window.screenWidth.toFloat() / guiScaledW
    val guiScaleY = window.screenHeight.toFloat() / guiScaledH
    if (!guiScaleX.isFinite() || !guiScaleY.isFinite()) return
    val originX = sx / guiScaleX
    val originY = sy / guiScaleY
    val hudScale = inventoryHud.scale
    val renderScale = (hudScale * baseScale) / guiScaleX
    if (!renderScale.isFinite()) return
    val graphics = event.graphics
    val pose = getPoseStack(graphics)
    val posePushed = pose?.let { posePush(it) } == true
    val poseTranslated = if (posePushed) poseTranslate(pose!!, originX.toDouble(), originY.toDouble(), 0.0) else false
    val poseScaled = if (poseTranslated) poseScale(pose!!, renderScale, renderScale, 1f) else false
    val poseActive = posePushed && poseTranslated && poseScaled

    GlStateManager._disableDepthTest()
    GlStateManager._enableBlend()
    GlStateManager._blendFuncSeparate(770, 771, 1, 0)

    val baseX = if (   poseActive) 0f else originX
    val baseY = if (poseActive) 0f else originY
    val coordScale = if (poseActive) 1f else renderScale
    for (i in 0 until 27) {
      val inventoryIndex = i + 9
      val stack = inventory.getItem(inventoryIndex)
      if (stack.isEmpty) continue

      val row = i / cols
      val col = i % cols
      if (row >= rows) continue

      val slotX = padding + col * (slotSize + slotGap) + itemOffset + itemNudgeX
      val slotY = padding + row * (slotSize + slotGap) + itemOffset + itemNudgeY
      val drawXF = baseX + slotX * coordScale
      val drawYF = baseY + slotY * coordScale
      if (!drawXF.isFinite() || !drawYF.isFinite()) continue
      val drawX = drawXF.roundToInt()
      val drawY = drawYF.roundToInt()

      graphics.renderItem(stack, drawX, drawY)
      graphics.renderItemDecorations(mc.font, stack, drawX, drawY)
    }
    if (poseActive) {
      posePop(pose!!)
    } else if (posePushed) {
      posePop(pose!!)
    }

    flushGraphics(graphics)
    GlStateManager._enableDepthTest()
  }

  private fun getPoseStack(graphics: GuiGraphics): Any? {
    if (poseMethod == null) {
      poseMethod = graphics.javaClass.methods.firstOrNull { it.name == "pose" || it.name == "poseStack" }
    }
    val byMethod = runCatching { poseMethod?.invoke(graphics) }.getOrNull()
    if (byMethod != null) return byMethod
    if (poseField == null) {
      poseField = graphics.javaClass.declaredFields.firstOrNull {
        it.type.name.contains("PoseStack", ignoreCase = true)
      }?.apply { isAccessible = true }
    }
    return runCatching { poseField?.get(graphics) }.getOrNull()
  }

  private fun posePush(pose: Any): Boolean {
    if (posePushMethod == null) {
      posePushMethod = pose.javaClass.methods.firstOrNull { it.name == "pushPose" || it.name == "push" }
    }
    return runCatching { posePushMethod?.invoke(pose) }.isSuccess
  }

  private fun posePop(pose: Any): Boolean {
    if (posePopMethod == null) {
      posePopMethod = pose.javaClass.methods.firstOrNull { it.name == "popPose" || it.name == "pop" }
    }
    return runCatching { posePopMethod?.invoke(pose) }.isSuccess
  }

  private fun poseTranslate(pose: Any, x: Double, y: Double, z: Double): Boolean {
    if (poseTranslateMethod == null) {
      poseTranslateMethod = pose.javaClass.methods.firstOrNull { it.name == "translate" && it.parameterTypes.size == 3 }
    }
    return runCatching { poseTranslateMethod?.invoke(pose, x, y, z) }.isSuccess
  }

  private fun poseScale(pose: Any, x: Float, y: Float, z: Float): Boolean {
    if (poseScaleMethod == null) {
      poseScaleMethod = pose.javaClass.methods.firstOrNull { it.name == "scale" && it.parameterTypes.size == 3 }
    }
    return runCatching { poseScaleMethod?.invoke(pose, x, y, z) }.isSuccess
  }

  private fun flushGraphics(graphics: GuiGraphics) {
    if (flushMethod == null) {
      flushMethod = graphics.javaClass.methods.firstOrNull { it.name == "flush" }
    }
    runCatching { flushMethod?.invoke(graphics) }
  }

}
