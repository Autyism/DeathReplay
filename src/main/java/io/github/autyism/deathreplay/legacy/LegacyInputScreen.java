package io.github.autyism.deathreplay.legacy;

//? if <1.21.9 {
/*import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/^*
 * Before 1.21.9 screens got key presses and clicks as plain numbers. The mod's screens that read
 * input (the look-around and replay screens) are written against the newer form and extend this
 * class on the older versions: it passes the old calls on to the newer form, and that form's
 * defaults back to the game.
 ^/
public abstract class LegacyInputScreen extends Screen {
	protected LegacyInputScreen(Component title) {
		super(title);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return this.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
	}

	public boolean keyPressed(KeyEvent input) {
		return super.keyPressed(input.key(), input.scancode(), input.modifiers());
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Clicks had no double-click flag yet; the mod's screens only pass it on.
		return this.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false);
	}

	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		return super.mouseClicked(click.x(), click.y(), click.button());
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double offsetX, double offsetY) {
		return this.mouseDragged(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), offsetX, offsetY);
	}

	public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
		return super.mouseDragged(click.x(), click.y(), click.button(), offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return this.mouseReleased(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)));
	}

	public boolean mouseReleased(MouseButtonEvent click) {
		return super.mouseReleased(click.x(), click.y(), click.button());
	}
}
*///?}
