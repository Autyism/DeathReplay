package io.github.autyism.deathreplay.camera;

import net.minecraft.network.chat.Component;

/**
 * Camera modes available on the death screen.
 */
public enum DeathCameraMode {
	/** Camera on a leash around the corpse; the mouse turns it. */
	THIRD_PERSON("deathreplay.camera.third_person"),
	/** Like third person, but the camera circles the corpse by itself. */
	ORBIT("deathreplay.camera.orbit"),
	/** Free flight: WASD + mouse. */
	FREE("deathreplay.camera.free");

	private final String translationKey;

	DeathCameraMode(String translationKey) {
		this.translationKey = translationKey;
	}

	public Component getDisplayName() {
		return Component.translatable(this.translationKey);
	}

	public DeathCameraMode next() {
		DeathCameraMode[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
