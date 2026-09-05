package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageEncoder;
import me.mapacheee.extendedhorizons.fakechunks.session.PlayerSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelInjectionServiceTest {

    @Test
    void thirdPartyMutationDoesNotTouchCanonicalPayload() {
        MutatingEncoder encoder = new MutatingEncoder();
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        ChannelInjectionService service = new ChannelInjectionService();
        ByteBuf canonical = Unpooled.buffer().writeBytes(new byte[] {1, 2, 3, 4});
        ByteBuf outbound = channel.alloc().buffer(canonical.readableBytes());
        outbound.writeBytes(canonical, canonical.readerIndex(), canonical.readableBytes());

        ChannelPromise promise = service.writeEncodedFuture(channel, outbound);
        assertNotNull(promise);
        channel.runPendingTasks();
        channel.flushOutbound();

        ByteBuf sent = channel.readOutbound();
        assertNotNull(sent);
        assertTrue(promise.isSuccess());
        assertEquals(1, encoder.writeCount);
        assertEquals(99, sent.getUnsignedByte(sent.readerIndex()));
        assertEquals(1, canonical.getUnsignedByte(canonical.readerIndex()));

        sent.release();
        canonical.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void inactiveChannelConsumesEncodedPayload() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close().syncUninterruptibly();
        ChannelInjectionService service = new ChannelInjectionService();
        ByteBuf payload = Unpooled.buffer().writeByte(1);

        ChannelPromise promise = service.writeEncodedFuture(channel, payload);

        assertNotNull(promise);
        assertTrue(promise.isDone());
        assertFalse(promise.isSuccess());
        assertEquals(0, payload.refCnt());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void readyPipelineStateSkipsRepeatedEventLoopWork() {
        EmbeddedChannel channel = new EmbeddedChannel();
        PlayerSession first = session();
        PlayerSession second = session();

        // Nothing injected yet: the per-tick inject() must still schedule its task.
        assertTrue(ChannelInjectionService.needsPipelineWork(channel, first, false));

        ChannelInjectionService.markPipelineState(channel, first, false);
        assertFalse(ChannelInjectionService.needsPipelineWork(channel, first, false));

        // An unresolved packet-id probe always forces the task to run again.
        assertTrue(ChannelInjectionService.needsPipelineWork(channel, first, true));

        // A different session on the same channel has to be rebound.
        assertTrue(ChannelInjectionService.needsPipelineWork(channel, second, false));

        // Marking while the probe is unresolved must not latch the ready flag.
        ChannelInjectionService.markPipelineState(channel, first, true);
        assertTrue(ChannelInjectionService.needsPipelineWork(channel, first, false));

        ChannelInjectionService.markPipelineState(channel, first, false);
        ChannelInjectionService.invalidatePipelineState(channel);
        assertTrue(ChannelInjectionService.needsPipelineWork(channel, first, false));

        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void handlerRemovalInvalidatesPipelineState() {
        EmbeddedChannel channel = new EmbeddedChannel();
        PlayerSession session = session();
        channel.pipeline().addLast(ChannelInjectionService.EH_HANDLER, new EhPacketHandler());
        ChannelInjectionService.markPipelineState(channel, session, false);
        assertFalse(ChannelInjectionService.needsPipelineWork(channel, session, false));

        channel.pipeline().remove(ChannelInjectionService.EH_HANDLER);

        assertTrue(ChannelInjectionService.needsPipelineWork(channel, session, false));
        assertFalse(channel.finishAndReleaseAll());
    }

    private static PlayerSession session() {
        return new PlayerSession(UUID.randomUUID(), UUID.randomUUID());
    }

    private static final class MutatingEncoder extends MessageToMessageEncoder<ByteBuf> {

        private int writeCount;

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            this.writeCount++;
            msg.setByte(msg.readerIndex(), 99);
            out.add(msg.retain());
        }
    }
}
