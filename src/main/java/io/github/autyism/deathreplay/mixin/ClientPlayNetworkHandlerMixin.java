package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.record.RecordedEvent;
import io.github.autyism.deathreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Listens to packets the server already sends, for the recorder. Read-only: no packet is
 * changed, cancelled, delayed or answered here.
 *
 * <p>Each handler is entered twice: first on the network thread, where vanilla immediately
 * re-schedules it, then on the render thread where it really runs. Only the second one counts.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Shadow
	private ClientLevel level;

	@Unique
	private static boolean deathreplay$onRenderThread() {
		return Minecraft.getInstance().isSameThread();
	}

	@Inject(method = "handleAddEntity", at = @At("HEAD"))
	private void deathreplay$onEntitySpawn(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread() && this.level != null) {
			Recorder.onEntitySpawn(this.level, packet);
		}
	}

	@Inject(method = "handleParticleEvent", at = @At("HEAD"))
	private void deathreplay$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.Particle(packet));
		}
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void deathreplay$onPlaySound(ClientboundSoundPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.Sound(packet));
		}
	}

	@Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
	private void deathreplay$onPlaySoundFromEntity(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.EntitySound(packet));
		}
	}

	@Inject(method = "handleLevelEvent", at = @At("HEAD"))
	private void deathreplay$onWorldEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.WorldEvent(packet));
		}
	}

	@Inject(method = "handleExplosion", at = @At("HEAD"))
	private void deathreplay$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.Explosion(packet));
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void deathreplay$onEntityStatus(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread() && Recorder.isCapturing() && this.level != null) {
			Entity entity = packet.getEntity(this.level);
			if (entity != null) {
				Recorder.onEvent(new RecordedEvent.EntityStatus(entity.getId(), packet.getEventId()));
			}
		}
	}

	@Inject(method = "handleAnimate", at = @At("HEAD"))
	private void deathreplay$onEntityAnimation(ClientboundAnimatePacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			int animation = packet.getAction();
			// Arm swings are sampled from the entity every tick; only the particle bursts need the packet.
			if (animation == ClientboundAnimatePacket.CRITICAL_HIT || animation == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
				Recorder.onEvent(new RecordedEvent.EntityAnimation(packet.getId(), animation));
			}
		}
	}

	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void deathreplay$onEntityDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.EntityDamage(packet));
		}
	}

	@Inject(method = "handleBlockDestruction", at = @At("HEAD"))
	private void deathreplay$onBlockBreakingProgress(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.BlockBreaking(packet));
		}
	}

	@Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
	private void deathreplay$onDeathMessage(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onDeathMessage(packet.message(), packet.playerId());
		}
	}
}
