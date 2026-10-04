package io.github.autyism.deathreplay.selftest;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.packet.Packet;

/**
 * Counts the packets the client sends to the server while the self-test asks for it, so the
 * test can prove that the mod's screens send nothing of their own. Fed by
 * {@code ClientConnectionAuditMixin}, which only exists in a self-test run.
 */
public final class PacketAudit {
	private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();
	private static volatile boolean running;

	private PacketAudit() {
	}

	/** Called for every packet leaving the client, from whatever thread sends it. */
	public static void onClientSend(Packet<?> packet) {
		if (running) {
			String name = packet.getClass().getName();
			COUNTS.merge(name.substring(name.lastIndexOf('.') + 1), 1, Integer::sum);
		}
	}

	static void start() {
		COUNTS.clear();
		running = true;
	}

	/** Stops counting and returns how many packets of each type were sent since {@link #start()}. */
	static Map<String, Integer> stop() {
		running = false;
		return new TreeMap<>(COUNTS);
	}
}
