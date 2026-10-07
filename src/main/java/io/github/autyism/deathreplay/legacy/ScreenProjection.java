package io.github.autyism.deathreplay.legacy;

//? if <1.21.6 {
/*import io.github.autyism.deathreplay.mixin.GameRendererFovInvoker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/^*
 * 1.21.6 added a way to find where a point of the world appears on the screen (for the locator
 * bar). This is the same projection for 1.21.5: the camera's turn, then the field of view the
 * world is drawn with.
 ^/
public final class ScreenProjection {
	private ScreenProjection() {
	}

	/^* The point in normalised screen coordinates: x and y in -1..1 across the screen, y pointing up. ^/
	public static Vec3 projectPointToScreen(Minecraft client, Vec3 point) {
		GameRenderer renderer = client.gameRenderer;
		Camera camera = renderer.getMainCamera();
		float fov = ((GameRendererFovInvoker) renderer).deathreplay$getFov(camera, 0.0F, true);
		Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
		Matrix4f projection = renderer.getProjectionMatrix(fov).mul(view);
		Vector3f projected = projection.transformProject(point.subtract(camera.getPosition()).toVector3f());
		return new Vec3(projected);
	}
}
*///?}
