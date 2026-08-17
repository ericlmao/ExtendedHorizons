package me.mapacheee.extendedhorizons.fakechunks.cache;

import io.netty.buffer.ByteBuf;
import io.netty.util.IllegalReferenceCountException;

final class CacheBufUtil {

    private CacheBufUtil() {
    }

    /**
     * Retains a readable duplicate of a cached payload, treating a concurrent
     * eviction (which releases the buffer between the cache lookup and the
     * retain) as a plain cache miss instead of letting the
     * IllegalReferenceCountException escape onto the event loop.
     */
    static ByteBuf retainReadableOrNull(ByteBuf payload) {
        if (payload == null || !payload.isReadable()) {
            return null;
        }
        try {
            return payload.retainedDuplicate();
        } catch (IllegalReferenceCountException raced) {
            return null;
        }
    }
}
