package io.github.autyi6969.deathreplay.record;

import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.util.math.BlockPos;

/**
 * Something that happened during one tick, other than entities moving: a block changed, or
 * the server told the client to show a particle, play a sound, or animate an entity.
 *
 * <p>The packet-backed events keep the packet object the client received, untouched; packets
 * are immutable once decoded.
 */
public sealed interface RecordedEvent {
	/** A block in the client world went from {@code oldState} to {@code newState}. */
	record BlockChange(BlockPos pos, BlockState oldState, BlockState newState) implements RecordedEvent {
	}

	record Particle(ParticleS2CPacket packet) implements RecordedEvent {
	}

	record Sound(PlaySoundS2CPacket packet) implements RecordedEvent {
	}

	record EntitySound(PlaySoundFromEntityS2CPacket packet) implements RecordedEvent {
	}

	/** Bundled sound + particle effects such as block breaking, potion splashes, door sounds. */
	record WorldEvent(WorldEventS2CPacket packet) implements RecordedEvent {
	}

	record Explosion(ExplosionS2CPacket packet) implements RecordedEvent {
	}

	/** Entity status byte: death poof, totem, hurt animations of specific mobs, etc. */
	record EntityStatus(int entityId, byte status) implements RecordedEvent {
	}

	/** Critical hit / enchanted hit sparkles (arm swings are sampled per tick instead). */
	record EntityAnimation(int entityId, int animationId) implements RecordedEvent {
	}

	/** An entity was hurt: red flash, hurt sound. */
	record EntityDamage(EntityDamageS2CPacket packet) implements RecordedEvent {
	}

	/** Cracks on a block somebody else is mining. */
	record BlockBreaking(BlockBreakingProgressS2CPacket packet) implements RecordedEvent {
	}
}
