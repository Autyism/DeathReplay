package io.github.autyi6969.deathreplay.camera;

import io.github.autyi6969.deathreplay.death.DeathView;
import org.jetbrains.annotations.Nullable;

/**
 * Single entry point the mixins ask: "is the mod steering the camera right now, and where to?".
 */
public final class CameraOverride {
	private CameraOverride() {
	}

	/** Cheap check, safe to call many times per frame. */
	public static boolean isActive() {
		return DeathView.isActive();
	}

	/**
	 * Advances the active camera by one rendered frame and returns its pose,
	 * or {@code null} when vanilla should keep control.
	 */
	@Nullable
	public static DetachedCamera frame(float tickProgress) {
		return DeathView.frame(tickProgress);
	}
}
