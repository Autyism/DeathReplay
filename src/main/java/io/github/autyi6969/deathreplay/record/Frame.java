package io.github.autyi6969.deathreplay.record;

import org.jetbrains.annotations.Nullable;

/**
 * One client tick of the recording: every nearby entity's sample plus the events of that tick.
 *
 * @param entities samples of all recorded entities, the recording player included
 * @param events   what happened during this tick, in the order it happened
 */
public record Frame(EntitySample[] entities, RecordedEvent[] events) {
	@Nullable
	public EntitySample find(int entityId) {
		for (EntitySample sample : this.entities) {
			if (sample.entityId == entityId) {
				return sample;
			}
		}

		return null;
	}
}
