package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class PacketIdSnifferHandler extends ChannelOutboundHandlerAdapter {

    private static final String[] PACKET_ID_METHODS = {"packetId", "id", "getId"};
    private static final Map<Class<?>, Optional<Method>> PACKET_ID_METHOD_CACHE = new ConcurrentHashMap<>();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        resolveFromPacket(msg);
        // Self-remove once both IDs are known so the per-packet hop does not
        // stay in the pipeline for the connection's whole lifetime.
        if (PacketIdRegistry.hasLevelChunkWithLightId() && PacketIdRegistry.hasChunkCacheRadiusId()) {
            ctx.pipeline().remove(this);
        }
        super.write(ctx, msg, promise);
    }

    static void resolveFromPacket(Object msg) {
        if (!PacketIdRegistry.hasLevelChunkWithLightId()) {
            if (msg instanceof ClientboundLevelChunkWithLightPacket) {
                int id = extractPacketId(msg);
                if (id >= 0) {
                    PacketIdRegistry.resolveLevelChunkWithLightId(id);
                }
            }
        }
        if (!PacketIdRegistry.hasChunkCacheRadiusId()) {
            if (msg instanceof ClientboundSetChunkCacheRadiusPacket) {
                int id = extractPacketId(msg);
                if (id >= 0) {
                    PacketIdRegistry.resolveChunkCacheRadiusId(id);
                }
            }
        }
    }

    private static int extractPacketId(Object packet) {
        Optional<Method> lookup = PACKET_ID_METHOD_CACHE.computeIfAbsent(
            packet.getClass(), PacketIdSnifferHandler::findPacketIdMethod);
        if (lookup.isEmpty()) {
            return -1;
        }
        try {
            Object result = lookup.get().invoke(packet);
            if (result instanceof Number num) {
                return num.intValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static Optional<Method> findPacketIdMethod(Class<?> clazz) {
        for (String methodName : PACKET_ID_METHODS) {
            try {
                Method method = clazz.getMethod(methodName);
                if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) {
                    return Optional.of(method);
                }
            } catch (Throwable ignored) {
            }
        }
        return Optional.empty();
    }
}
