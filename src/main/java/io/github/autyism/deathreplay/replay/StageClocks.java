package io.github.autyism.deathreplay.replay;

//? if >=26.1 {
/*import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.ClientClockManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import org.jetbrains.annotations.Nullable;

/^*
 * Time of day from 26.1 on. The game keeps its world clocks per connection, so every level of a
 * server shares them. A replay's stage must show the time of the death instead (a death at night
 * replays as night): it gets clocks of its own, stopped at the values recorded with the terrain.
 * The stage takes them while it is being built ({@code ClientLevelMixin}), because its sky and
 * light are tied to the clocks it has then.
 ^/
public final class StageClocks {
	/^* Clocks for the stage that is being built right now. ^/
	@Nullable
	private static ClientClockManager pending;

	private StageClocks() {
	}

	/^* Every world clock of the server as {@code level} shows it now, by clock id. ^/
	public static Map<Identifier, Long> capture(ClientLevel level) {
		Map<Identifier, Long> clocks = new HashMap<>();
		level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).listElements()
			.forEach(clock -> clocks.put(clock.key().identifier(), level.clockManager().getTotalTicks(clock)));
		return clocks;
	}

	/^*
	 * Readies stopped clocks for the next stage. A clock that was not recorded shows
	 * {@code timeOfDay}, the recorded time of the dimension's main clock.
	 ^/
	static void prepare(RegistryAccess registries, Map<Identifier, Long> recorded, long timeOfDay, long gameTime) {
		Map<Holder<WorldClock>, ClockNetworkState> states = new HashMap<>();
		registries.lookupOrThrow(Registries.WORLD_CLOCK).listElements().forEach(clock ->
			states.put(clock, new ClockNetworkState(recorded.getOrDefault(clock.key().identifier(), timeOfDay), 0.0F, 0.0F)));
		ClientClockManager clocks = new ClientClockManager();
		clocks.handleUpdates(gameTime, states);
		pending = clocks;
	}

	/^* The stage is built; whatever it did not take is dropped. ^/
	static void done() {
		pending = null;
	}

	/^*
	 * Asked by {@code ClientLevelMixin} the first time a level wants its clocks: the stage being
	 * built gets the prepared ones, every other level keeps the game's own.
	 ^/
	@Nullable
	public static ClientClockManager adopt(ClientLevel level) {
		ClientClockManager clocks = pending;
		Minecraft client = Minecraft.getInstance();
		if (clocks == null || !client.isSameThread() || level == client.level) {
			return null;
		}

		pending = null;
		return clocks;
	}
}
*///?}
