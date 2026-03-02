package org.cobalt.mixin.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.cobalt.api.event.impl.render.NvgEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

  @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;incrementFrameNumber()V", shift = At.Shift.AFTER))
  public void renderNvg(DeltaTracker counter, boolean tick, CallbackInfo callbackInfo) {
    new NvgEvent().post();
    new org.cobalt.api.event.impl.render.GuiPostRenderEvent().post();
  }

}
