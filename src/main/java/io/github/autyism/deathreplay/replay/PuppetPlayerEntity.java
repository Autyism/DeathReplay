package io.github.autyism.deathreplay.replay;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Stand-in for a recorded player during a replay.
 *
 * <p>A puppet needs its own entity UUID (the real player may still be in the world), but the
 * skin is looked up by UUID. This class pins the tab-list entry of the real player so the
 * puppet keeps that player's skin.
 */
public class PuppetPlayerEntity extends RemotePlayer {
	@Nullable
	private final PlayerInfo listEntry;

	public PuppetPlayerEntity(ClientLevel world, GameProfile profile, @Nullable PlayerInfo listEntry) {
		super(world, profile);
		this.listEntry = listEntry;
	}

	@Nullable
	@Override
	protected PlayerInfo getPlayerInfo() {
		return this.listEntry;
	}
}
