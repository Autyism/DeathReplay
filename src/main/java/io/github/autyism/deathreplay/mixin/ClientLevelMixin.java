package io.github.autyism.deathreplay.mixin;

//? if >=26.1 {
/*import io.github.autyism.deathreplay.replay.StageClocks;
import net.minecraft.client.ClientClockManager;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/^*
 * 26.1+ only (added by {@link DeathReplayMixinPlugin#getMixins()}): a replay's stage keeps its own
 * clocks, stopped at the time of the death, instead of the connection's live ones. See {@link StageClocks}.
 ^/
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Unique
	private ClientClockManager deathreplay$clocks;

	@Inject(method = "clockManager", at = @At("HEAD"), cancellable = true)
	private void deathreplay$stageClocks(CallbackInfoReturnable<ClientClockManager> cir) {
		if (this.deathreplay$clocks == null) {
			this.deathreplay$clocks = StageClocks.adopt((ClientLevel) (Object) this);
		}

		if (this.deathreplay$clocks != null) {
			cir.setReturnValue(this.deathreplay$clocks);
		}
	}
}
*///?}
