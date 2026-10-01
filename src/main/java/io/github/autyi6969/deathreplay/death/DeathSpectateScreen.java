package io.github.autyi6969.deathreplay.death;

import io.github.autyi6969.deathreplay.camera.DeathCameraMode;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/**
 * The interactive part of the death screen free camera: hides the death screen's red tint and
 * buttons, grabs the mouse for looking around and lets WASD fly the camera in free mode.
 * Esc goes back to the vanilla death screen it was opened from.
 */
public class DeathSpectateScreen extends Screen {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFC0C0C0;

	private final DeathScreen parent;

	public DeathSpectateScreen(DeathScreen parent) {
		super(Text.translatable("deathreplay.spectate.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		DeathView.setControlling(this.client, true);
	}

	@Override
	public void removed() {
		DeathView.setControlling(this.client, false);
	}

	@Override
	public void tick() {
		if (!DeathView.isActive()) {
			// Respawned (or the feature was switched off): hand control back to vanilla,
			// which shows the death screen if the player is still dead and the game otherwise.
			this.client.setScreen(null);
			return;
		}

		// The mouse can only be grabbed while the window has focus; retry after alt-tabbing back.
		DeathView.regrabMouse(this.client);
	}

	@Override
	public void close() {
		this.client.setScreen(DeathView.isActive() ? this.parent : null);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (this.client.options.togglePerspectiveKey.matchesKey(input)) {
			DeathView.cycleMode();
			return true;
		}

		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		DeathView.scroll(verticalAmount);
		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		DeathCameraMode mode = DeathView.getMode();
		Text perspectiveKey = this.client.options.togglePerspectiveKey.getBoundKeyLocalizedText();
		int centerX = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.mode", mode.getDisplayName()), centerX, 8, TEXT_COLOR);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint", perspectiveKey), centerX, 20, HINT_COLOR);
		if (mode == DeathCameraMode.FREE) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.free"), centerX, 32, HINT_COLOR);
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.free2"), centerX, 44, HINT_COLOR);
		} else {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("deathreplay.spectate.hint.orbit"), centerX, 32, HINT_COLOR);
		}
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		// No blur, no dimming, no red tint: the point of this screen is an unobstructed view.
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
