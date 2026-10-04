package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.replay.Replay;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During a replay only the replay's own puppets are drawn: the present-day entities would
 * otherwise stand in the middle of the past scene.
 */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void deathreplay$hideDuringReplay(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		if (Replay.hidesEntity(entity)) {
			cir.setReturnValue(false);
		}
	}
}
