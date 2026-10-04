package io.github.autyism.deathreplay.mixin;

import io.github.autyism.deathreplay.record.RecordedEvent;
import io.github.autyism.deathreplay.record.Recorder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
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
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Shadow
	private ClientWorld world;

	@Unique
	private static boolean deathreplay$onRenderThread() {
		return MinecraftClient.getInstance().isOnThread();
	}

	@Inject(method = "onEntitySpawn", at = @At("HEAD"))
	private void deathreplay$onEntitySpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread() && this.world != null) {
			Recorder.onEntitySpawn(this.world, packet);
		}
	}

	@Inject(method = "onParticle", at = @At("HEAD"))
	private void deathreplay$onParticle(ParticleS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.Particle(packet));
		}
	}

	@Inject(method = "onPlaySound", at = @At("HEAD"))
	private void deathreplay$onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.Sound(packet));
		}
	}

	@Inject(method = "onPlaySoundFromEntity", at = @At("HEAD"))
	private void deathreplay$onPlaySoundFromEntity(PlaySoundFromEntityS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.EntitySound(packet));
		}
	}

	@Inject(method = "onWorldEvent", at = @At("HEAD"))
	private void deathreplay$onWorldEvent(WorldEventS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.WorldEvent(packet));
		}
	}

	@Inject(method = "onExplosion", at = @At("HEAD"))
	private void deathreplay$onExplosion(ExplosionS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.Explosion(packet));
		}
	}

	@Inject(method = "onEntityStatus", at = @At("HEAD"))
	private void deathreplay$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread() && Recorder.isCapturing() && this.world != null) {
			Entity entity = packet.getEntity(this.world);
			if (entity != null) {
				Recorder.onEvent(new RecordedEvent.EntityStatus(entity.getId(), packet.getStatus()));
			}
		}
	}

	@Inject(method = "onEntityAnimation", at = @At("HEAD"))
	private void deathreplay$onEntityAnimation(EntityAnimationS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			int animation = packet.getAnimationId();
			// Arm swings are sampled from the entity every tick; only the particle bursts need the packet.
			if (animation == EntityAnimationS2CPacket.CRIT || animation == EntityAnimationS2CPacket.ENCHANTED_HIT) {
				Recorder.onEvent(new RecordedEvent.EntityAnimation(packet.getEntityId(), animation));
			}
		}
	}

	@Inject(method = "onEntityDamage", at = @At("HEAD"))
	private void deathreplay$onEntityDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.EntityDamage(packet));
		}
	}

	@Inject(method = "onBlockBreakingProgress", at = @At("HEAD"))
	private void deathreplay$onBlockBreakingProgress(BlockBreakingProgressS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onEvent(new RecordedEvent.BlockBreaking(packet));
		}
	}

	@Inject(method = "onDeathMessage", at = @At("HEAD"))
	private void deathreplay$onDeathMessage(DeathMessageS2CPacket packet, CallbackInfo ci) {
		if (deathreplay$onRenderThread()) {
			Recorder.onDeathMessage(packet.message(), packet.playerId());
		}
	}
}
