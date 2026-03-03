package org.cobalt.mixin.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.KeyboardInput;
import org.cobalt.internal.dungeons.DungeonsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

  @Redirect(
    method = "tick",
    at = @At(
      value = "INVOKE",
      target = "Lnet/minecraft/client/KeyMapping;isDown()Z",
      ordinal = 1
    ),
    require = 0
  )
  private boolean cobalt$forceBackwardForBonzoStaff(KeyMapping keyBinding) {
    boolean originalBackward = keyBinding.isDown();
    if (DungeonsModule.INSTANCE.shouldPressBackward()) {
      return true;
    }
    return originalBackward;
  }
}
