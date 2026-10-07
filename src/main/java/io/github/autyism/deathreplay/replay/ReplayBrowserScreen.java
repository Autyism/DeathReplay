package io.github.autyism.deathreplay.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.github.autyism.deathreplay.DeathReplayClient;
import io.github.autyism.deathreplay.record.Recorder;
import io.github.autyism.deathreplay.record.Recording;
import io.github.autyism.deathreplay.record.ReplayFileReader;
import io.github.autyism.deathreplay.record.ReplayFileWriter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Lists what can be watched: the latest death still in memory, and the replays saved to the
 * {@code deathreplay} folder (newest first). Replays can only be played while in a world.
 */
public class ReplayBrowserScreen extends Screen {
	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int NOTE_COLOR = 0xFFC0C0C0;
	private static final int ERROR_COLOR = 0xFFFF7070;
	private static final int ROW_WIDTH = 260;
	private static final int ROW_HEIGHT = 22;
	private static final int FILES_PER_PAGE = 5;
	private static final String FILE_PREFIX = "death_";
	private static final String FILE_SUFFIX = ".nbt";

	@Nullable
	private final Screen parent;
	private List<Path> files = List.of();
	private int page;
	@Nullable
	private Component error;

	public ReplayBrowserScreen(@Nullable Screen parent) {
		super(Component.translatable("deathreplay.browser.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.files = listFiles(ReplayFileWriter.directory(this.minecraft));
		int pages = Math.max(1, (this.files.size() + FILES_PER_PAGE - 1) / FILES_PER_PAGE);
		this.page = Math.min(this.page, pages - 1);
		boolean canPlay = Replay.canPlay(this.minecraft);
		int x = this.width / 2 - ROW_WIDTH / 2;
		int y = 34;

		Recording latest = Recorder.getLast();
		Button latestButton = Button.builder(Component.translatable("deathreplay.browser.latest"), button -> {
			if (!Replay.open(this.minecraft)) {
				this.error = Component.translatable("deathreplay.browser.cannot_play");
			}
		}).bounds(x, y, ROW_WIDTH, 20).build();
		latestButton.active = latest != null && canPlay;
		this.addRenderableWidget(latestButton);
		y += ROW_HEIGHT + 6;

		for (int i = this.page * FILES_PER_PAGE; i < Math.min(this.files.size(), (this.page + 1) * FILES_PER_PAGE); i++) {
			Path file = this.files.get(i);
			Button button = Button.builder(label(file), pressed -> this.play(file)).bounds(x, y, ROW_WIDTH, 20).build();
			button.active = canPlay;
			this.addRenderableWidget(button);
			y += ROW_HEIGHT;
		}

		int bottom = this.height - 28;
		int quarter = (ROW_WIDTH - 12) / 4;
		Button previous = Button.builder(Component.translatable("deathreplay.browser.previous"), button -> this.turnPage(-1)).bounds(x, bottom, quarter, 20).build();
		Button next = Button.builder(Component.translatable("deathreplay.browser.next"), button -> this.turnPage(1)).bounds(x + quarter + 4, bottom, quarter, 20).build();
		previous.active = this.page > 0;
		next.active = this.page < pages - 1;
		this.addRenderableWidget(previous);
		this.addRenderableWidget(next);
		this.addRenderableWidget(Button.builder(Component.translatable("deathreplay.browser.open_folder"), button -> this.openFolder())
			.bounds(x + (quarter + 4) * 2, bottom, quarter, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
			.bounds(x + (quarter + 4) * 3, bottom, quarter, 20).build());
	}

	private void turnPage(int direction) {
		this.page = Math.max(0, this.page + direction);
		this.rebuildWidgets();
	}

	private void openFolder() {
		Path directory = ReplayFileWriter.directory(this.minecraft);
		try {
			Files.createDirectories(directory);
			Util.getPlatform().openPath(directory);
		} catch (IOException e) {
			DeathReplayClient.LOGGER.warn("Could not open {}", directory, e);
		}
	}

	/** Loads a saved replay and plays it. Public so the self-test can go through the same path as a click. */
	public void play(Path file) {
		this.error = null;
		if (!Replay.canPlay(this.minecraft)) {
			this.error = Component.translatable("deathreplay.browser.need_world");
			return;
		}

		try {
			Recording recording = ReplayFileReader.read(file, this.minecraft.level.registryAccess());
			if (!Replay.open(this.minecraft, recording)) {
				this.error = Component.translatable("deathreplay.browser.cannot_play");
			}
		} catch (IOException e) {
			DeathReplayClient.LOGGER.warn("Could not read replay {}", file, e);
			this.error = Component.translatable("deathreplay.browser.unreadable", file.getFileName().toString());
		}
	}

	/** Saved replays, newest first. */
	public static List<Path> listFiles(Path directory) {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}

		try (Stream<Path> stream = Files.list(directory)) {
			List<Path> found = new ArrayList<>(stream
				.filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX) && path.getFileName().toString().endsWith(FILE_SUFFIX))
				.toList());
			found.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
			return found;
		} catch (IOException e) {
			DeathReplayClient.LOGGER.warn("Could not list {}", directory, e);
			return List.of();
		}
	}

	/** "death_2026-10-01_12-32-27.nbt" becomes "2026-10-01 12:32:27  (25 KB)". */
	private static Component label(Path file) {
		String name = file.getFileName().toString();
		String stamp = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
		int split = stamp.indexOf('_');
		String shown = split > 0 ? stamp.substring(0, split) + " " + stamp.substring(split + 1).replaceFirst("-", ":").replaceFirst("-", ":") : stamp;
		long kiloBytes;
		try {
			kiloBytes = Math.max(1L, Files.size(file) / 1024L);
		} catch (IOException e) {
			kiloBytes = 0L;
		}

		return Component.translatable("deathreplay.browser.file", shown, kiloBytes);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int centerX = this.width / 2;
		context.drawCenteredString(this.font, this.title, centerX, 14, TITLE_COLOR);

		int noteY = this.height - 44;
		if (this.error != null) {
			context.drawCenteredString(this.font, this.error, centerX, noteY, ERROR_COLOR);
		} else if (!Replay.canPlay(this.minecraft)) {
			context.drawCenteredString(this.font, Component.translatable("deathreplay.browser.need_world"), centerX, noteY, NOTE_COLOR);
		} else if (this.files.isEmpty()) {
			context.drawCenteredString(this.font, Component.translatable("deathreplay.browser.empty"), centerX, noteY, NOTE_COLOR);
		} else {
			int pages = (this.files.size() + FILES_PER_PAGE - 1) / FILES_PER_PAGE;
			context.drawCenteredString(this.font, Component.translatable("deathreplay.browser.page", this.page + 1, pages, this.files.size()), centerX, noteY, NOTE_COLOR);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
