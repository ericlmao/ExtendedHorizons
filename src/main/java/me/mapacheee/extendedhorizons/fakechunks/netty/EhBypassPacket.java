package me.mapacheee.extendedhorizons.fakechunks.netty;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

public record EhBypassPacket(Object payload) implements ReferenceCounted {

    @Override
    public int refCnt() {
        return this.payload instanceof ReferenceCounted counted ? counted.refCnt() : 1;
    }

    @Override
    public ReferenceCounted retain() {
        ReferenceCountUtil.retain(this.payload);
        return this;
    }

    @Override
    public ReferenceCounted retain(int increment) {
        if (this.payload instanceof ReferenceCounted counted) {
            counted.retain(increment);
        }
        return this;
    }

    @Override
    public ReferenceCounted touch() {
        ReferenceCountUtil.touch(this.payload);
        return this;
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        ReferenceCountUtil.touch(this.payload, hint);
        return this;
    }

    @Override
    public boolean release() {
        return ReferenceCountUtil.release(this.payload);
    }

    @Override
    public boolean release(int decrement) {
        if (this.payload instanceof ReferenceCounted counted) {
            return counted.release(decrement);
        }
        return false;
    }
}
