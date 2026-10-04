package io.github.autyism.deathreplay.replay;

import net.minecraft.text.Text;

/**
 * Camera modes of the replay.
 */
public enum ReplayView {
	/** Through the eyes of the recorded player. */
	FIRST_PERSON("deathreplay.view.first_person"),
	/** On a leash around the recorded player; the mouse turns it. */
	THIRD_PERSON("deathreplay.view.third_person"),
	/** Free flight: WASD + mouse. */
	FREE("deathreplay.view.free");

	private final String translationKey;

	ReplayView(String translationKey) {
		this.translationKey = translationKey;
	}

	public Text getDisplayName() {
		return Text.translatable(this.translationKey);
	}

	public ReplayView next() {
		ReplayView[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
