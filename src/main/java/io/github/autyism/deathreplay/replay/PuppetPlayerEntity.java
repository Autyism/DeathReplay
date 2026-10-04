package io.github.autyism.deathreplay.replay;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;

/**
 * Stand-in for a recorded player during a replay.
 *
 * <p>A puppet needs its own entity UUID (the real player may still be in the world), but the
 * skin is looked up by UUID. This class pins the tab-list entry of the real player so the
 * puppet keeps that player's skin.
 */
public class PuppetPlayerEntity extends OtherClientPlayerEntity {
	@Nullable
	private final PlayerListEntry listEntry;

	public PuppetPlayerEntity(ClientWorld world, GameProfile profile, @Nullable PlayerListEntry listEntry) {
		super(world, profile);
		this.listEntry = listEntry;
	}

	@Nullable
	@Override
	protected PlayerListEntry getPlayerListEntry() {
		return this.listEntry;
	}
}
