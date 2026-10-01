package io.github.autyi6969.deathreplay.camera;

import io.github.autyi6969.deathreplay.death.DeathSpectateScreen;
import io.github.autyi6969.deathreplay.death.DeathView;
import io.github.autyi6969.deathreplay.replay.Replay;
import io.github.autyi6969.deathreplay.replay.ReplayScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;

/**
 * Single entry point the mixins ask: "is the mod steering the camera right now, and where to?".
 * A running replay takes precedence over the death screen view it was opened from.
 */
public final class CameraOverride {
	private CameraOverride() {
	}

	/** Cheap check, safe to call many times per frame. */
	public static boolean isActive() {
		return Replay.isActive() || DeathView.isActive();
	}

	/** The replay's stage while playback runs, otherwise {@code null}. See {@link Replay#getStage()}. */
	@Nullable
	public static ClientWorld getStage() {
		return Replay.getStage();
	}

	/**
	 * True while one of the mod's full-view screens is open. Hotbar, hearts and chat belong to
	 * the present, not to the scene being looked at, so the in-game HUD is not drawn then.
	 * (Not done through the F1 "hide HUD" option: that would also hide entity name tags.)
	 */
	public static boolean hidesHud() {
		Screen screen = MinecraftClient.getInstance().currentScreen;
		return screen instanceof ReplayScreen || screen instanceof DeathSpectateScreen;
	}

	/**
	 * Advances the active camera by one rendered frame and returns its pose,
	 * or {@code null} when vanilla should keep control.
	 */
	@Nullable
	public static DetachedCamera frame(float tickProgress) {
		if (Replay.isActive()) {
			DetachedCamera camera = Replay.frame(tickProgress);
			if (camera != null) {
				return camera;
			}
		}

		return DeathView.frame(tickProgress);
	}
}
