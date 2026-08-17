package me.mapacheee.extendedhorizons.fakechunks.farplayers.backend;

import com.mojang.datafixers.util.Pair;
import com.thewinterframework.service.annotation.Service;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.fakechunks.farplayers.model.FarPlayerState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import me.mapacheee.extendedhorizons.util.NmsCompat;

import java.util.Collections;
import java.util.List;

@Service
public final class PaperFarPlayerBackend implements FarPlayerBackend {

    @Override
    public Object createSpawnPacket(int entityId, FarPlayerState state) {
        return new ClientboundAddEntityPacket(
            entityId,
            state.uuid(),
            state.x(),
            state.y(),
            state.z(),
            state.pitch(),
            state.yaw(),
            NmsCompat.PLAYER_ENTITY_TYPE,
            0,
            Vec3.ZERO,
            state.headYaw()
        );
    }

    @Override
    public Object createMovePacket(int entityId, FarPlayerState state) {
        return new ClientboundTeleportEntityPacket(
            entityId,
            new PositionMoveRotation(
                new Vec3(state.x(), state.y(), state.z()),
                Vec3.ZERO,
                state.yaw(),
                state.pitch()
            ),
            Collections.emptySet(),
            true
        );
    }

    @Override
    public Object createDespawnPacket(int entityId) {
        return new ClientboundRemoveEntitiesPacket(entityId);
    }

    @Override
    public Object createEquipmentPacket(int entityId, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        return new ClientboundSetEquipmentPacket(entityId, equipment);
    }

    @Override
    public Object createMetadataPacket(int entityId, List<SynchedEntityData.DataValue<?>> metadata) {
        return new ClientboundSetEntityDataPacket(entityId, metadata);
    }

    // The packet has no public (entityId, headYaw) constructor, so it must be
    // decoded from bytes. Reuse a tiny per-thread buffer instead of allocating a
    // fresh ByteBuf + FriendlyByteBuf wrapper per tracked player per move tick.
    private static final ThreadLocal<FriendlyByteBuf> ROTATE_HEAD_BUF =
        ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(8)));

    @Override
    public Object createRotateHeadPacket(int entityId, float headYaw) {
        FriendlyByteBuf buf = ROTATE_HEAD_BUF.get();
        buf.clear();
        buf.writeVarInt(entityId);
        buf.writeByte((byte) (headYaw * 256.0F / 360.0F));
        return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
    }
}
