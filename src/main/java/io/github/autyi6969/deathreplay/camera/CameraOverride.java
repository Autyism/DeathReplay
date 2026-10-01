package io.github.autyi6969.deathreplay.camera;

import io.github.autyi6969.deathreplay.death.DeathView;
import io.github.autyi6969.deathreplay.replay.Replay;
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
