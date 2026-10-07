package io.github.autyism.deathreplay.legacy;

//? if <1.21.9 {
/*/^* A click as 1.21.9 and later pass it to screens: where, and which button (see {@link LegacyInputScreen}). ^/
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) {
	public int button() {
		return this.buttonInfo.button();
	}
}
*///?}
