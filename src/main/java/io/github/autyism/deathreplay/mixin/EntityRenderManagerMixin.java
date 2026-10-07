package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.replay.Replay;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During a replay only the replay's own puppets are drawn: the present-day entities would
 * otherwise stand in the middle of the past scene.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderManagerMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	//? if >=26.3 {
	/*private void deathreplay$hideDuringReplay(Entity entity, Frustum frustum, double x, double y, double z, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
	*///?} else
	private void deathreplay$hideDuringReplay(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		if (Replay.hidesEntity(entity)) {
			cir.setReturnValue(false);
		}
	}
}
