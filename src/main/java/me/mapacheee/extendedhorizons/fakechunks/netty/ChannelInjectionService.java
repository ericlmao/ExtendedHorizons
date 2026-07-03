package me.mapacheee.extendedhorizons.fakechunks.netty;

import com.thewinterframework.service.annotation.lifecycle.OnDisable;
import com.thewinterframework.service.annotation.Service;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class ChannelInjectionService {

    public static final String EH_HANDLER = "eh_packet_handler";
    public static final String EH_PACKET_ID_PROBE_HANDLER = "eh_packet_id_probe";
    private final Set<Channel> injectedChannels = ConcurrentHashMap.newKeySet();

    public void inject(Player player) {
        this.inject(player, null);
    }

    public void inject(Player player, PlayerSession session) {
        Channel channel = this.resolveChannel(player);
        if (channel == null || !channel.isActive()) {
            return;
        }
        Runnable action = () -> {
            if (channel.pipeline().get("encoder") != null
                && needsPacketIdProbe()
                && channel.pipeline().get(EH_PACKET_ID_PROBE_HANDLER) == null) {
                channel.pipeline().addBefore("encoder", EH_PACKET_ID_PROBE_HANDLER, new EhPacketIdProbeHandler());
            }
            if (channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler) {
                handler.setSession(session);
                this.trackInjectedChannel(channel);
                removePacketIdProbeIfResolved(channel);
                return;
            }
            if (channel.pipeline().get("packet_handler") == null) {
                return;
            }
            EhPacketHandler handler = new EhPacketHandler();
            handler.setSession(session);
            channel.pipeline().addBefore("packet_handler", EH_HANDLER, handler);
            this.trackInjectedChannel(channel);
            PacketIdRegistry.resolveFromEncoder(channel);
            removePacketIdProbeIfResolved(channel);
        };
        this.runOnEventLoop(channel, action);
    }

    public void uninject(Player player) {
        this.uninject(this.resolveChannel(player));
    }

    private void uninject(Channel channel) {
        if (channel == null) {
            return;
        }
        this.injectedChannels.remove(channel);
        Runnable action = () -> {
            if (channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler) {
                handler.setSession(null);
                channel.pipeline().remove(EH_HANDLER);
            }
            if (channel.pipeline().get(EH_PACKET_ID_PROBE_HANDLER) != null) {
                channel.pipeline().remove(EH_PACKET_ID_PROBE_HANDLER);
            }
        };
        this.runOnEventLoop(channel, action);
    }

    public void bindSession(Channel channel, PlayerSession session) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        Runnable action = () -> {
            if (channel.pipeline().get(EH_HANDLER) instanceof EhPacketHandler handler) {
                handler.setSession(session);
            }
        };
        this.runOnEventLoop(channel, action);
    }

    public boolean writeBypass(Player player, Object payload) {
        Channel channel = this.resolveChannel(player);
        return this.writeBypass(channel, payload);
    }

    public boolean writeBypass(Channel channel, Object payload) {
        if (channel == null || !channel.isActive()) {
            ReferenceCountUtil.release(payload);
            return false;
        }
        Runnable action = () -> {
            if (!channel.isActive()) {
                ReferenceCountUtil.release(payload);
                return;
            }
            try {
                channel.write(new EhBypassPacket(payload), channel.voidPromise());
            } catch (Throwable throwable) {
                ReferenceCountUtil.release(payload);
            }
        };
        if (!this.runOnEventLoop(channel, action)) {
            ReferenceCountUtil.release(payload);
            return false;
        }
        return true;
    }

    public ChannelPromise writeBypassFuture(Channel channel, Object payload) {
        if (channel == null || !channel.isActive()) {
            return null;
        }
        ChannelPromise promise = channel.newPromise();
        Runnable action = () -> {
            if (!channel.isActive()) {
                ReferenceCountUtil.release(payload);
                promise.tryFailure(new IllegalStateException("Channel inactive"));
                return;
            }
            try {
                channel.write(new EhBypassPacket(payload), promise);
            } catch (Throwable throwable) {
                ReferenceCountUtil.release(payload);
                promise.tryFailure(throwable);
            }
        };
        if (!this.runOnEventLoop(channel, action)) {
            ReferenceCountUtil.release(payload);
            promise.tryFailure(new IllegalStateException("Channel event loop unavailable"));
        }
        return promise;
    }

    public void flush(Player player) {
        Channel channel = this.resolveChannel(player);
        this.flush(channel);
    }

    public void flush(Channel channel) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        this.runOnEventLoop(channel, channel::flush);
    }

    public void executeOnEventLoop(Channel channel, Runnable runnable) {
        if (channel == null || !channel.isActive() || runnable == null) {
            return;
        }
        this.runOnEventLoop(channel, runnable);
    }

    public Channel resolveChannel(Player player) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return null;
        }
        ServerPlayer serverPlayer = craftPlayer.getHandle();
        if (serverPlayer == null) {
            return null;
        }
        return serverPlayer.connection.connection.channel;
    }

    @OnDisable
    public void onDisable() {
        Set<Channel> channels = new HashSet<>(this.injectedChannels);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = this.resolveChannel(player);
            if (channel != null) {
                channels.add(channel);
            }
        }
        for (Channel channel : channels) {
            this.uninject(channel);
        }
        this.injectedChannels.clear();
    }

    private boolean runOnEventLoop(Channel channel, Runnable action) {
        if (channel == null || action == null) {
            return false;
        }
        EventLoop eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            action.run();
            return true;
        }
        try {
            eventLoop.execute(action);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void trackInjectedChannel(Channel channel) {
        if (this.injectedChannels.add(channel)) {
            channel.closeFuture().addListener(future -> this.injectedChannels.remove(channel));
        }
    }

    private static boolean needsPacketIdProbe() {
        return !PacketIdRegistry.hasLevelChunkWithLightId() || !PacketIdRegistry.hasChunkCacheRadiusId();
    }

    private static void removePacketIdProbeIfResolved(Channel channel) {
        if (needsPacketIdProbe()) {
            return;
        }
        if (channel.pipeline().get(EH_PACKET_ID_PROBE_HANDLER) != null) {
            channel.pipeline().remove(EH_PACKET_ID_PROBE_HANDLER);
        }
    }
}
