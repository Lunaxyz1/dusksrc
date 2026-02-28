package org.cobalt.api.hud.modules

import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer

class WatermarkModule : Module("Watermark") {

  private val textSize = 18f

  val watermark = hudElement("watermark", "Watermark", "Displays Dutt Client branding") {
    anchor = HudAnchor.TOP_LEFT
    offsetX = 10f
    offsetY = 10f

    val text = setting(TextSetting("Text", "Display text", "Dutt Client"))
    val color = setting(ColorSetting("Color", "Text color", ThemeManager.currentTheme.accent))
    val shadow = setting(CheckboxSetting("Shadow", "Show text shadow", false))
    val background = setting(CheckboxSetting("Background", "Show background box", false))

    width { NVGRenderer.textWidth(text.value, textSize) + (if (background.value) 16f else 0f) }
    height { textSize + (if (background.value) 12f else 4f) }

    render { screenX, screenY, _ ->
      val padX = if (background.value) 8f else 0f
      val padY = if (background.value) 6f else 0f

      if (background.value) {
        NVGRenderer.rect(
          screenX, screenY,
          NVGRenderer.textWidth(text.value, textSize) + 16f,
          textSize + 12f,
          ThemeManager.currentTheme.panel, 6f
        )
      }

      val textX = screenX + padX
      val textY = screenY + padY
      if (shadow.value) {
        NVGRenderer.textShadow(text.value, textX, textY, textSize, color.value)
      } else {
        NVGRenderer.text(text.value, textX, textY, textSize, color.value)
      }
    }
  }
}
