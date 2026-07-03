package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EhPacketHandlerTest {

    @Test
    void bypassPacketWritesByteBufPayloadInsteadOfWrapper() {
        EmbeddedChannel channel = new EmbeddedChannel(new EhPacketHandler());
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(42);

        assertTrue(channel.writeOutbound(new EhBypassPacket(payload)));

        Object outbound = channel.readOutbound();
        assertSame(payload, outbound);
        assertNull(channel.readOutbound());
        assertEquals(1, payload.refCnt());

        ReferenceCountUtil.release(outbound);
        assertFalse(channel.finish());
    }

    @Test
    void releasingBypassPacketReleasesPayload() {
        ByteBuf payload = Unpooled.buffer();
        EhBypassPacket bypass = new EhBypassPacket(payload);

        assertTrue(bypass.release());
        assertEquals(0, payload.refCnt());
    }
}
