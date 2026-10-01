package io.github.autyi6969.deathreplay.record;

import java.util.List;
import java.util.UUID;

import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A frozen recording: the ticks leading up to one death. Never modified after it is built.
 *
 * @param frames         one entry per client tick, oldest first
 * @param deathFrame     index of the frame in which the player was first seen dead
 * @param playerEntityId entity id of the recording player inside the frames
 * @param playerName     name of the recording player
 * @param playerUuid     UUID of the recording player
 * @param dimension      dimension the death happened in
 * @param deathPos       where the player died
 * @param deathMessage   the death message shown on the death screen, if the server sent one
 * @param deathTimeMillis wall-clock time of the death
 */
public record Recording(
	List<Frame> frames,
	int deathFrame,
	int playerEntityId,
	String playerName,
	UUID playerUuid,
	RegistryKey<World> dimension,
	Vec3d deathPos,
	@Nullable Text deathMessage,
	long deathTimeMillis
) {
	public static final int TICKS_PER_SECOND = 20;

	public int tickCount() {
		return this.frames.size();
	}

	public float seconds() {
		return this.frames.size() / (float) TICKS_PER_SECOND;
	}

	@Nullable
	public EntitySample playerAt(int frameIndex) {
		return this.frames.get(frameIndex).find(this.playerEntityId);
	}
}
