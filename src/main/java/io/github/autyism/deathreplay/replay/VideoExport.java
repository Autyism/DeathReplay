package io.github.autyism.deathreplay.replay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.autyism.deathreplay.DeathReplayClient;
import io.github.autyism.deathreplay.config.DeathReplayConfig;
import io.github.autyism.deathreplay.record.Recording;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Turns a replay into an mp4 file. The replay plays once from the start in the view chosen; right after the game has
 * drawn the world (before the menus and the HUD) the picture is copied from the graphics card and handed to ffmpeg on
 * another thread, which encodes it (H.264). The video has a fixed frame rate: a frame due while the game was slower is
 * the previous picture again, so the video always has the replay's real length. No sound.
 *
 * <p>ffmpeg is looked for in config/deathreplay, config/menuvideo (Menu Video ships it) and on the PATH.
 */
public final class VideoExport {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	/** Pictures waiting for ffmpeg; when it falls behind, pictures are repeated instead of piling up memory */
	private static final int QUEUE = 6;

	/** pixels null: the last picture again */
	private record Job(byte[] pixels, int copies) {
	}

	private static boolean active;
	private static boolean finishing;
	private static Process ffmpeg;
	private static Thread writer;
	private static BlockingQueue<Job> queue;
	private static int width, height, fps;
	private static long startNanos;
	private static int nextFrame;
	private static int pendingCopies;
	private static int framesQueued, framesRepeated;
	private static Path output;
	private static int totalFrames;
	private static volatile String failure;
	/** a reusable read-back buffer of the right size */
	@Nullable
	private static AutoCloseable readBuffer;
	private static long readSize;
	private static long startedAt, lastHook;
	private static boolean readBusy;

	private VideoExport() {
	}

	private static RenderTarget target(Minecraft client) {
		//? if >=26.2 {
		/*return client.gameRenderer.mainRenderTarget();
		*///?} else
		return client.getMainRenderTarget();
	}

	/** The fast read-back (1.21.6 to 26.1) gives the picture bottom row first; the game's screenshot read-back top row first */
	private static boolean bottomUp() {
		//? if >=1.21.6 && <26.2 {
		return true;
		//?} else
		/*return false;*/
	}

	public static boolean isActive() {
		return active;
	}

	/** 0..1 while exporting */
	public static float progress() {
		return totalFrames <= 0 ? 0F : Math.min(1F, nextFrame / (float) totalFrames);
	}

	/** ffmpeg.exe (or ffmpeg) from the places it is looked for, or null */
	@Nullable
	public static Path findFfmpeg(Minecraft client) {
		String exe = File.separatorChar == '\\' ? "ffmpeg.exe" : "ffmpeg";
		Path config = client.gameDirectory.toPath().resolve("config");
		for (Path p : new Path[]{config.resolve(DeathReplayClient.NAMESPACE).resolve(exe), config.resolve("menuvideo").resolve(exe)}) {
			if (Files.isRegularFile(p)) return p;
		}
		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(File.pathSeparator)) {
				try {
					Path p = Path.of(dir.trim()).resolve(exe);
					if (Files.isRegularFile(p)) return p;
				} catch (RuntimeException ignored) {
					// not a path
				}
			}
		}
		return null;
	}

	public static Path folder(Minecraft client) {
		return client.gameDirectory.toPath().resolve(DeathReplayClient.NAMESPACE).resolve("videos");
	}

	/** Starts exporting the replay that is open now. Returns a message for the player when it cannot. */
	@Nullable
	public static Component start(Minecraft client) {
		Recording recording = Replay.getRecording();
		if (active || recording == null || !Replay.isPlayback()) return null;
		Path exe = findFfmpeg(client);
		if (exe == null) {
			Path dir = client.gameDirectory.toPath().resolve("config").resolve(DeathReplayClient.NAMESPACE);
			try {
				Files.createDirectories(dir);
			} catch (IOException ignored) {
				// shown anyway
			}
			return Component.translatable("deathreplay.video.no_ffmpeg", dir.toString());
		}
		RenderTarget target = target(client);
		width = target.width;
		height = target.height;
		fps = Math.max(10, Math.min(60, DeathReplayConfig.get().videoFps));
		int maxHeight = DeathReplayConfig.get().videoMaxHeight;
		try {
			Files.createDirectories(folder(client));
			output = folder(client).resolve((recording.clip() ? "clip_" : "death_") + FILE_TIME.format(LocalDateTime.now()) + ".mp4");
			// the picture comes bottom row first; even sizes for the encoder; at most maxHeight tall
			String scale = maxHeight > 0 && height > maxHeight ? "scale=-2:" + maxHeight : "scale=trunc(iw/2)*2:trunc(ih/2)*2";
			List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-hide_banner", "-loglevel", "error", "-y",
					"-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", Integer.toString(fps), "-i", "-",
					"-vf", (bottomUp() ? "vflip," : "") + scale, "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
					"-movflags", "+faststart", output.toString()));
			ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(folder(client).resolve("ffmpeg.log").toFile());
			ffmpeg = pb.start();
		} catch (IOException e) {
			DeathReplayClient.LOGGER.error("Could not start ffmpeg", e);
			return Component.translatable("deathreplay.video.failed", e.getMessage());
		}
		queue = new ArrayBlockingQueue<>(QUEUE);
		failure = null;
		OutputStream in = new BufferedOutputStream(ffmpeg.getOutputStream(), 1 << 20);
		writer = new Thread(() -> {
			byte[] last = null;
			try {
				while (true) {
					Job job = queue.poll(200, TimeUnit.MILLISECONDS);
					if (job == null) {
						if (finishing && queue.isEmpty()) break;
						continue;
					}
					byte[] pixels = job.pixels() != null ? job.pixels() : last;
					if (job.pixels() != null) last = job.pixels();
					if (pixels == null) continue;
					for (int i = 0; i < job.copies(); i++) in.write(pixels);
				}
				in.close();
			} catch (IOException | InterruptedException e) {
				failure = e.toString();
				try {
					in.close();
				} catch (IOException ignored) {
					// closing anyway
				}
			}
		}, "Death Replay video writer");
		writer.setDaemon(true);
		writer.start();
		totalFrames = (int) Math.ceil(recording.tickCount() / (double) Recording.TICKS_PER_SECOND * fps);
		nextFrame = 0;
		pendingCopies = 0;
		framesQueued = framesRepeated = 0;
		readBusy = false;
		active = true;
		finishing = false;
		startNanos = 0L;
		startedAt = System.nanoTime();
		lastHook = 0L;
		Replay.restart();
		Replay.setPlaying(true);
		DeathReplayClient.LOGGER.info("Exporting a video: {}x{} at {} fps, {} frames, to {}", width, height, fps, totalFrames, output);
		return null;
	}

	/** Stops without keeping the file (Esc, the replay closed) */
	public static void cancel(Minecraft client) {
		if (!active) return;
		active = false;
		finishing = true;
		if (ffmpeg != null) ffmpeg.destroyForcibly();
		try {
			if (output != null) Files.deleteIfExists(output);
		} catch (IOException ignored) {
			// it may still be locked; ffmpeg is gone anyway
		}
		releaseBuffer();
		if (client.player != null) client.player.displayClientMessage(Component.translatable("deathreplay.video.cancelled"), false);
	}

	/** Once a second from the replay screen: the picture hook must have run, else this version cannot make videos */
	public static void watchdog(Minecraft client) {
		if (active && !finishing && lastHook == 0L && System.nanoTime() - startedAt > 3_000_000_000L) {
			DeathReplayClient.LOGGER.warn("Video export: the picture is never handed over on this game version; cancelled");
			cancel(client);
			if (client.player != null) client.player.displayClientMessage(Component.translatable("deathreplay.video.unsupported"), false);
		}
	}

	/** Right after the world is drawn, every frame. */
	public static void afterWorld(Minecraft client) {
		if (!active || finishing) return;
		lastHook = System.nanoTime();
		if (!Replay.isPlayback()) {
			cancel(client);
			return;
		}
		if (Replay.getRecording() != null && Replay.isAtEnd() && !Replay.isPlaying()) {
			finish(client);
			return;
		}
		// frames follow the replay's own clock (ticks plus the part of the tick drawn now), so a slow game repeats
		// pictures instead of making the video shorter
		double seconds = (Math.max(0, Replay.getTick() - 1) + client.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / Recording.TICKS_PER_SECOND;
		int due = Math.min(totalFrames, (int) Math.floor(seconds * fps) + 1);
		int copies = due - nextFrame;
		if (copies <= 0) return;
		nextFrame = due;
		pendingCopies += copies;
		if (readBusy) {
			// the last read-back is not done: its picture will stand for these frames too
			framesRepeated += copies;
			return;
		}
		readBack(client);
	}

	private static void readBack(Minecraft client) {
		RenderTarget target = target(client);
		if (target.getColorTexture() == null || target.width != width || target.height != height) {
			// the window was resized: the video size cannot change in the middle
			failure = "window resized";
			finish(client);
			return;
		}
		readBusy = true;
		//? if >=1.21.6 && <26.2 {
		com.mojang.blaze3d.textures.GpuTexture texture = target.getColorTexture();
		int size = width * height * texture.getFormat().pixelSize();
		if (readBuffer == null || readSize != size) {
			releaseBuffer();
			readBuffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Death Replay video frame",
				com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, size);
			readSize = size;
		}
		com.mojang.blaze3d.buffers.GpuBuffer buffer = (com.mojang.blaze3d.buffers.GpuBuffer) readBuffer;
		com.mojang.blaze3d.systems.CommandEncoder encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToBuffer(texture, buffer, 0, () -> {
			readBusy = false;
			if (!active || finishing) {
				// ended meanwhile: the buffer was left for this read to finish with
				if (buffer != readBuffer) buffer.close();
				return;
			}
			byte[] pixels = new byte[size];
			try (com.mojang.blaze3d.buffers.GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
				view.data().get(0, pixels, 0, pixels.length);
			}
			hand(pixels);
		}, 0);
		//?} else {
		/*net.minecraft.client.Screenshot.takeScreenshot(target, image -> {
			readBusy = false;
			try (image) {
				if (!active || finishing) {
					return;
				}
				byte[] pixels = new byte[width * height * 4];
				org.lwjgl.system.MemoryUtil.memByteBuffer(image.getPointer(), pixels.length).get(0, pixels, 0, pixels.length);
				hand(pixels);
			}
		});
		*///?}
	}

	/** A picture read back: to the writer, standing for every frame due since the last one */
	private static void hand(byte[] pixels) {
		int copies = pendingCopies;
		pendingCopies = 0;
		if (queue.offer(new Job(pixels, copies))) {
			framesQueued += copies;
		} else {
			// ffmpeg is behind: keep the count, the next picture stands in for these frames
			pendingCopies += copies;
			framesRepeated += copies;
		}
	}

	private static void releaseBuffer() {
		if (readBuffer != null) {
			// a read still on its way closes the buffer itself when it arrives
			if (!readBusy) {
				try {
					readBuffer.close();
				} catch (Exception ignored) {
					// nothing to free
				}
			}
			readBuffer = null;
		}
	}

	private static void finish(Minecraft client) {
		if (!active || finishing) return;
		// the end: the last picture until the video is as long as the replay
		int missing = totalFrames - nextFrame + pendingCopies;
		if (missing > 0) {
			try {
				queue.put(new Job(null, missing));
			} catch (InterruptedException ignored) {
				// shorter by a moment
			}
		}
		nextFrame = totalFrames;
		finishing = true;
		releaseBuffer();
		Path file = output;
		Process process = ffmpeg;
		Thread write = writer;
		int queued = framesQueued, repeated = framesRepeated;
		Thread waiter = new Thread(() -> {
			boolean ok = false;
			try {
				write.join(120_000);
				ok = process.waitFor(300, TimeUnit.SECONDS) && process.exitValue() == 0 && failure == null && Files.size(file) > 0;
			} catch (InterruptedException | IOException e) {
				failure = e.toString();
			}
			boolean success = ok;
			DeathReplayClient.LOGGER.info("Video export {}: {} frames written ({} repeated while the game was slow), {}", success ? "done" : "failed",
					queued, repeated, failure == null ? "" : failure);
			client.execute(() -> {
				active = false;
				if (client.player == null) return;
				if (success) {
					MutableComponent link = Component.literal(file.getFileName().toString()).withStyle(Style.EMPTY.withUnderlined(true).withColor(ChatFormatting.AQUA)
							//? if >=1.21.5 {
							.withClickEvent(new ClickEvent.OpenFile(file.getParent())));
							//?} else
							/*.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getParent().toString())));*/
					client.player.displayClientMessage(Component.translatable("deathreplay.video.saved", link), false);
				} else {
					client.player.displayClientMessage(Component.translatable("deathreplay.video.failed", failure == null ? "ffmpeg" : failure), false);
				}
			});
		}, "Death Replay video finish");
		waiter.setDaemon(true);
		waiter.start();
	}
}
