package io.github.autyism.deathreplay.record;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.level.block.state.BlockState;

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

	record Particle(ClientboundLevelParticlesPacket packet) implements RecordedEvent {
	}

	record Sound(ClientboundSoundPacket packet) implements RecordedEvent {
	}

	record EntitySound(ClientboundSoundEntityPacket packet) implements RecordedEvent {
	}

	/** Bundled sound + particle effects such as block breaking, potion splashes, door sounds. */
	record WorldEvent(ClientboundLevelEventPacket packet) implements RecordedEvent {
	}

	record Explosion(ClientboundExplodePacket packet) implements RecordedEvent {
	}

	/** Entity status byte: death poof, totem, hurt animations of specific mobs, etc. */
	record EntityStatus(int entityId, byte status) implements RecordedEvent {
	}

	/** Critical hit / enchanted hit sparkles (arm swings are sampled per tick instead). */
	record EntityAnimation(int entityId, int animationId) implements RecordedEvent {
	}

	/** An entity was hurt: red flash, hurt sound. */
	record EntityDamage(ClientboundDamageEventPacket packet) implements RecordedEvent {
	}

	/** Cracks on a block somebody else is mining. */
	record BlockBreaking(ClientboundBlockDestructionPacket packet) implements RecordedEvent {
	}
}
