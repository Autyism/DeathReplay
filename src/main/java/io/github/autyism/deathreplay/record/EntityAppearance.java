package io.github.autyism.deathreplay.record;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import com.mojang.authlib.GameProfile;
import org.jetbrains.annotations.Nullable;

/**
 * Everything needed to rebuild what an entity looked like, apart from where it was.
 *
 * <p>Immutable. One instance is shared by every frame in which the entity's look did not
 * change, so a frame only costs a position sample per entity.
 *
 * @param type        entity type
 * @param uuid        the entity's real UUID
 * @param profile     game profile (skin, name) for players, otherwise {@code null}
 * @param localPlayer whether this is the recording player
 * @param spawnPacket the spawn packet the server sent for this entity; carries type-specific
 *                    spawn data (facing of item frames, falling block state...). {@code null}
 *                    for the local player, which is never spawned by a packet.
 * @param trackedData the entity's synced data that differs from the type's defaults
 *                    (pose, flags, health, custom name, variant...)
 * @param equipment   worn and held items indexed by {@code EquipmentSlot.ordinal()},
 *                    or {@code null} for non-living entities
 */
public record EntityAppearance(
	EntityType<?> type,
	UUID uuid,
	@Nullable GameProfile profile,
	boolean localPlayer,
	@Nullable ClientboundAddEntityPacket spawnPacket,
	List<SynchedEntityData.DataValue<?>> trackedData,
	ItemStack @Nullable [] equipment
) {
}
