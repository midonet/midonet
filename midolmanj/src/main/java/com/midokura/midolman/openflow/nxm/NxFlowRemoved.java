/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.util.U16;

public class NxFlowRemoved extends NxMessage {
    private static final int MINIMUM_LENGTH = 56;

    protected long cookie;
    protected short priority;
    protected OFFlowRemovedReason reason;
    protected int durationSeconds;
    protected int durationNanoseconds;
    protected short idleTimeout;
    protected long packetCount;
    protected long byteCount;
    protected NxMatch nxm;
    protected ByteBuffer serializedBuf;

    public NxFlowRemoved() {
        super(NxType.NXT_FLOW_REMOVED);
    }

    public NxFlowRemoved(long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, NxMatch nxm) {
        super(NxType.NXT_FLOW_REMOVED);
        this.cookie = cookie;
        this.priority = priority;
        this.reason = reason;
        this.durationSeconds = durationSeconds;
        this.durationNanoseconds = durationNanoseconds;
        this.idleTimeout = idleTimeout;
        this.packetCount = packetCount;
        this.byteCount = byteCount;
        this.nxm = nxm;
    }

    public long getCookie() {
        return cookie;
    }

    public short getPriority() {
        return priority;
    }

    public OFFlowRemovedReason getReason() {
        return reason;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public int getDurationNanoseconds() {
        return durationNanoseconds;
    }

    public short getIdleTimeout() {
        return idleTimeout;
    }

    public long getPacketCount() {
        return packetCount;
    }

    public long getByteCount() {
        return byteCount;
    }

    public NxMatch getNxMatch() {
        return nxm;
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        cookie = data.getLong();
        priority = data.getShort();
        reason = OFFlowRemovedReason.values()[(0xff & data.get())];
        data.get(); // padding
        durationSeconds = data.getInt();
        durationNanoseconds = data.getInt();
        idleTimeout = data.getShort();
        short nxMatchLen = data.getShort();
        packetCount = data.getLong();
        byteCount = data.getLong();

        // read NxMatch
        int limit = data.limit();
        data.limit(data.position()+nxMatchLen);
        try {
            nxm = NxMatch.deserialize(data);
        } catch (NxmIOException e) {
            throw new RuntimeException(e);
        }
        data.limit(limit);

        // read zero bytes
        int zeroBytes = (nxMatchLen + 7) / 8*8 - nxMatchLen;
        for (int i=0; i<zeroBytes; i++) {
            data.get();
        }
    }

    @Override
    public void writeTo(ByteBuffer data) {
        if (null == serializedBuf)
            prepareSerialize();
        data.put(serializedBuf);
    }

    /**
     * Must be called before trying to serialize. OFMessageAsyncStream queries
     * the length before calling writeTo in order to decide whether the
     * serialized message will fit in the output buffer.
     */
    public void prepareSerialize() {
        // calculate match len, which could be 0
        ByteBuffer nxmBuffer = null;
        int nxMatchLen = 0;

        if (nxm != null) {
            nxmBuffer = nxm.serialize();
            nxMatchLen = nxmBuffer.limit();
        }
        int zeroBytes = (nxMatchLen + 7) / 8*8 - nxMatchLen;
        int totalLen =
                MINIMUM_LENGTH + nxMatchLen + zeroBytes;
        super.setLength(U16.t(totalLen));
        ByteBuffer data = ByteBuffer.allocate(totalLen);

        // now that we set the length, we can write the header
        super.writeTo(data);
        data.putLong(cookie);
        data.putShort(priority);
        int reasonIdx = 0;
        while (reasonIdx < OFFlowRemovedReason.values().length &&
                !reason.equals(OFFlowRemovedReason.values()[reasonIdx]))
            reasonIdx++;
        data.put((byte)reasonIdx);
        data.put((byte) 0); // padding
        data.putInt(durationSeconds);
        data.putInt(durationNanoseconds);
        data.putShort(idleTimeout);
        data.putShort((short)nxMatchLen);
        data.putLong(packetCount);
        data.putLong(byteCount);

        if (nxmBuffer != null) {
            data.put(nxmBuffer);
        }
        // put zero bytes for padding
        for (int i=0; i<zeroBytes; i++) {
            data.put((byte) 0);
        }
        data.flip();
        serializedBuf = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxFlowRemoved that = (NxFlowRemoved) o;

        if (byteCount != that.byteCount) return false;
        if (cookie != that.cookie) return false;
        if (durationNanoseconds != that.durationNanoseconds) return false;
        if (durationSeconds != that.durationSeconds) return false;
        if (idleTimeout != that.idleTimeout) return false;
        if (packetCount != that.packetCount) return false;
        if (priority != that.priority) return false;
        if (nxm != null ? !nxm.equals(that.nxm) : that.nxm != null)
            return false;
        if (reason != that.reason) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) (cookie ^ (cookie >>> 32));
        result = 31 * result + (int) priority;
        result = 31 * result + (reason != null ? reason.hashCode() : 0);
        result = 31 * result + durationSeconds;
        result = 31 * result + durationNanoseconds;
        result = 31 * result + (int) idleTimeout;
        result = 31 * result + (int) (packetCount ^ (packetCount >>> 32));
        result = 31 * result + (int) (byteCount ^ (byteCount >>> 32));
        result = 31 * result + (nxm != null ? nxm.hashCode() : 0);
        return result;
    }
}
