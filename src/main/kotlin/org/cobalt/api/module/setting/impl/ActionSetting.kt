package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting

/**
 * Action button setting. Not persisted; used to trigger an action from the UI.
 */
class ActionSetting(
  name: String,
  description: String,
  val buttonLabel: String,
  private val onClick: () -> Unit,
) : Setting<Boolean>(name, description, false) {

  override val defaultValue: Boolean = false

  override fun read(element: JsonElement) {
    // No persisted state.
  }

  override fun write(): JsonElement {
    return JsonPrimitive(false)
  }

  fun trigger() {
    onClick()
  }
}
