package io.github.autyism.deathreplay.replay;

import io.github.autyism.deathreplay.mixin.LivingHealthAccessor;
import io.github.autyism.deathreplay.record.EntitySample;
import io.github.autyism.deathreplay.record.Frame;
import io.github.autyism.deathreplay.record.RecordedEvent;
import io.github.autyism.deathreplay.record.Recording;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.ArrayList;
import java.util.List;

/**
 * Every hit the recording player took, for the replay's timeline: when, how much health it cost (from the health the
 * server sent) and what did it (the damage type and the attacker, as far as the recording shows).
 */
public final class DamageTimeline {
	/** One hit: the frame, health before and after, and what did it */
	public record Hit(int frame, float before, float after, Component source) {
		public float lost() {
			return Math.max(0F, this.before - this.after);
		}
	}

	private static Recording cachedFor;
	private static List<Hit> cached = List.of();
	private static float[] cachedHealth = new float[0];

	private DamageTimeline() {
	}

	public static List<Hit> hits(Recording recording) {
		build(recording);
		return cached;
	}

	/** The player's health in every frame (-1 where unknown) */
	public static float[] health(Recording recording) {
		build(recording);
		return cachedHealth;
	}

	private static void build(Recording recording) {
		if (recording == cachedFor) return;
		cachedFor = recording;
		List<Frame> frames = recording.frames();
		float[] health = new float[frames.size()];
		int healthId = LivingHealthAccessor.deathreplay$healthId().id();
		float last = -1F;
		for (int i = 0; i < frames.size(); i++) {
			EntitySample player = frames.get(i).find(recording.playerEntityId());
			float h = -1F;
			if (player != null) {
				for (SynchedEntityData.DataValue<?> value : player.appearance.trackedData()) {
					if (value.id() == healthId && value.value() instanceof Float f) {
						h = f;
						break;
					}
				}
			}
			health[i] = h >= 0F ? h : last;
			if (h >= 0F) last = h;
		}
		List<Hit> hits = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			ClientboundDamageEventPacket damage = null;
			for (RecordedEvent event : frames.get(i).events()) {
				if (event instanceof RecordedEvent.EntityDamage d && d.packet().entityId() == recording.playerEntityId()) {
					damage = d.packet();
					break;
				}
			}
			float before = i > 0 ? health[i - 1] : health[i];
			// the health packet can come a tick after the damage event: look one or two ticks ahead
			float after = health[i];
			for (int k = 1; k <= 2 && i + k < frames.size() && after >= before; k++) after = health[i + k];
			boolean dropped = before >= 0F && health[i] >= 0F && health[i] < before - 0.01F;
			if (damage != null) {
				hits.add(new Hit(i, before, Math.min(after, before), source(recording, damage, i)));
			} else if (dropped && (hits.isEmpty() || hits.getLast().frame() < i - 2)) {
				hits.add(new Hit(i, before, health[i], Component.translatable("deathreplay.damage.unknown")));
			}
		}
		cached = List.copyOf(hits);
		cachedHealth = health;
	}

	private static Component source(Recording recording, ClientboundDamageEventPacket damage, int frame) {
		String msgId = damage.sourceType().value().msgId();
		String key = "deathreplay.damage." + msgId;
		Component type = net.minecraft.locale.Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(msgId);
		// the packet carries the attacker's entity id (-1 = none)
		int causeId = damage.sourceCauseId();
		if (causeId >= 0) {
			EntitySample cause = frames(recording, frame).find(causeId);
			if (cause != null) {
				Component who = cause.appearance.profile() != null ? Component.literal(cause.appearance.profile().name())
						: cause.appearance.type().getDescription();
				return Component.translatable("deathreplay.damage.by", type, who);
			}
		}
		return type;
	}

	private static Frame frames(Recording recording, int frame) {
		return recording.frames().get(frame);
	}
}
