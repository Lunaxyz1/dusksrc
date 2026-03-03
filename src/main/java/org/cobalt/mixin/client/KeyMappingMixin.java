package org.cobalt.mixin.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.cobalt.api.util.player.MovementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public class KeyMappingMixin {

  @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
  private void cobalt$lockMovement(CallbackInfoReturnable<Boolean> cir) {
    if (!MovementManager.isMovementLocked) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.options == null) {
      return;
    }
    KeyMapping self = (KeyMapping) (Object) this;
    if (self == mc.options.keyUp) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedForward);
      return;
    }
    if (self == mc.options.keyDown) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedBackward);
      return;
    }
    if (self == mc.options.keyLeft) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedLeft);
      return;
    }
    if (self == mc.options.keyRight) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedRight);
      return;
    }
    if (self == mc.options.keyJump) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedJump);
      return;
    }
    if (self == mc.options.keyShift) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedShift);
      return;
    }
    if (self == mc.options.keySprint) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedSprint);
      return;
    }
    if (self == mc.options.keyAttack || self == mc.options.keyUse) {
      cir.setReturnValue(false);
    }
  }
}
