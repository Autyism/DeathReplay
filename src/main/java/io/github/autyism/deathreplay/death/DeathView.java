package io.github.autyism.deathreplay.death;

import io.github.autyism.deathreplay.camera.CameraInput;
import io.github.autyism.deathreplay.camera.DeathCameraMode;
import io.github.autyism.deathreplay.camera.DetachedCamera;
import io.github.autyism.deathreplay.config.DeathReplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Death screen free camera.
 *
 * <p>While the local player is dead (and has not respawned), the render camera is detached
 * from the corpse. It is a pure view change: the player entity is not moved, no packet is
 * sent, and the override ends the very frame the player is alive again or is replaced by the
 * respawned player, so nothing can be observed after respawning.
 */
public final class DeathView {
	public static final double MIN_DISTANCE = 1.5;
	public static final double MAX_DISTANCE = 20.0;
	public static final double MIN_FLY_SPEED = 2.0;
	public static final double MAX_FLY_SPEED = 40.0;
	private static final double DEFAULT_DISTANCE = 4.5;
	private static final double DEFAULT_FLY_SPEED = 10.0;
	private static final float DEFAULT_PITCH = 25.0F;
	private static final float ORBIT_DEGREES_PER_SECOND = 15.0F;
	private static final double SPRINT_MULTIPLIER = 2.5;
	/** The corpse lies on the ground; aim a little above its feet. */
	private static final double TARGET_HEIGHT = 0.6;

	private static final DetachedCamera CAMERA = new DetachedCamera();
	private static final CameraInput INPUT = new CameraInput();
	private static final CameraInput.State INPUT_STATE = new CameraInput.State();
	/** Extra input injected by the self-test, which cannot press real keys. */
	private static final CameraInput.State SYNTHETIC_INPUT = new CameraInput.State();

	@Nullable
	private static LocalPlayer decedent;
	@Nullable
	private static ClientLevel world;
	private static DeathCameraMode mode = DeathCameraMode.THIRD_PERSON;
	private static double distance = DEFAULT_DISTANCE;
	private static double flySpeed = DEFAULT_FLY_SPEED;
	/** True while the spectate screen is open, i.e. the player is steering the camera. */
	private static boolean controlling;
	private static long lastFrameNanos;

	private DeathView() {
	}

	public static boolean isActive() {
		return decedent != null;
	}

	public static DeathCameraMode getMode() {
		return mode;
	}

	public static DetachedCamera getCamera() {
		return CAMERA;
	}

	public static CameraInput.State getSyntheticInput() {
		return SYNTHETIC_INPUT;
	}

	// ---------------------------------------------------------------- lifecycle

	/** Called every client tick. Starts and stops the override as the player dies and respawns. */
	public static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (isActive()) {
			if (!isStillValid(client)) {
				deactivate(client);
			}
		} else if (player != null && client.level != null && player.isDeadOrDying() && DeathReplayConfig.get().deathFreeCamera) {
			activate(player, client.level);
		}
	}

	private static boolean isStillValid(Minecraft client) {
		return client.player == decedent
			&& client.level == world
			&& decedent != null
			&& decedent.isDeadOrDying()
			&& DeathReplayConfig.get().deathFreeCamera;
	}

	private static void activate(LocalPlayer player, ClientLevel clientWorld) {
		decedent = player;
		world = clientWorld;
		mode = DeathReplayConfig.get().deathCameraMode;
		distance = DEFAULT_DISTANCE;
		flySpeed = DEFAULT_FLY_SPEED;
		controlling = false;
		lastFrameNanos = 0L;
		// Start behind the direction the player was facing, looking slightly down at the body.
		CAMERA.setRotation(player.getYRot(), DEFAULT_PITCH);
		CAMERA.orbit(clientWorld, player, target(player, 1.0F), distance);
	}

	private static void deactivate(Minecraft client) {
		decedent = null;
		world = null;
		controlling = false;
		// The spectate screen notices on its next tick and closes itself.
		INPUT.releaseMouse(client);
	}

	// ---------------------------------------------------------------- per-frame camera

	/**
	 * Advances the camera by one rendered frame. Returns {@code null} as soon as the player is
	 * no longer the dead player this view was started for.
	 */
	@Nullable
	public static DetachedCamera frame(float tickProgress) {
		if (!isActive()) {
			return null;
		}

		Minecraft client = Minecraft.getInstance();
		if (!isStillValid(client)) {
			// Do not wait for the next tick: the camera must be back the moment the player respawns.
			deactivate(client);
			return null;
		}

		long now = System.nanoTime();
		double seconds = lastFrameNanos == 0L ? 0.0 : Mth.clamp((now - lastFrameNanos) / 1.0e9, 0.0, 0.1);
		lastFrameNanos = now;

		CameraInput.State input = INPUT_STATE;
		if (controlling) {
			INPUT.poll(client, input);
		} else {
			input.forward = input.strafe = input.up = 0.0;
			input.sprint = false;
			input.yawDelta = input.pitchDelta = 0.0F;
		}

		double forward = Mth.clamp(input.forward + SYNTHETIC_INPUT.forward, -1.0, 1.0);
		double strafe = Mth.clamp(input.strafe + SYNTHETIC_INPUT.strafe, -1.0, 1.0);
		double up = Mth.clamp(input.up + SYNTHETIC_INPUT.up, -1.0, 1.0);
		float yawDelta = input.yawDelta + SYNTHETIC_INPUT.yawDelta * (float) seconds;
		float pitchDelta = input.pitchDelta + SYNTHETIC_INPUT.pitchDelta * (float) seconds;

		Vec3 target = target(decedent, tickProgress);
		switch (mode) {
			case THIRD_PERSON -> {
				CAMERA.rotate(yawDelta, pitchDelta);
				CAMERA.orbit(world, decedent, target, distance);
			}
			case ORBIT -> {
				CAMERA.rotate(yawDelta + ORBIT_DEGREES_PER_SECOND * (float) seconds, pitchDelta);
				CAMERA.orbit(world, decedent, target, distance);
			}
			case FREE -> {
				CAMERA.rotate(yawDelta, pitchDelta);
				double speed = flySpeed * (input.sprint || SYNTHETIC_INPUT.sprint ? SPRINT_MULTIPLIER : 1.0);
				CAMERA.fly(forward, strafe, up, speed * seconds);
			}
		}

		return CAMERA;
	}

	private static Vec3 target(LocalPlayer player, float tickProgress) {
		return player.getPosition(tickProgress).add(0.0, TARGET_HEIGHT, 0.0);
	}

	// ---------------------------------------------------------------- controls

	public static void setMode(DeathCameraMode newMode) {
		mode = newMode;
	}

	public static void cycleMode() {
		setMode(mode.next());
	}

	/** Mouse wheel: leash length in the two orbiting modes, flight speed in free mode. */
	public static void scroll(double amount) {
		if (mode == DeathCameraMode.FREE) {
			flySpeed = Mth.clamp(flySpeed * Math.pow(1.2, amount), MIN_FLY_SPEED, MAX_FLY_SPEED);
		} else {
			distance = Mth.clamp(distance * Math.pow(0.85, amount), MIN_DISTANCE, MAX_DISTANCE);
		}
	}

	/** Opens the interactive view (mouse-look, WASD) on top of the death screen. */
	public static void openSpectate(Minecraft client) {
		if (isActive() && client.screen instanceof DeathScreen deathScreen) {
			client.setScreen(new DeathSpectateScreen(deathScreen));
		}
	}

	static void setControlling(Minecraft client, boolean value) {
		controlling = value && isActive();
		if (controlling) {
			INPUT.grabMouse(client);
		} else {
			INPUT.releaseMouse(client);
		}
	}

	static void regrabMouse(Minecraft client) {
		if (controlling) {
			INPUT.grabMouse(client);
		}
	}
}
