/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.openflow.util.U16;

import com.midokura.midolman.openflow.PacketInReason;

public class NxPacketIn extends NxMessage {
    private static final int MINIMUM_LENGTH = 40;

    protected int bufferId;
    protected short totalFrameLen;
    protected PacketInReason reason;
    protected byte tableId;
    protected long cookie;
    protected NxMatch nxm;
    protected byte[] packet;
    private ByteBuffer serializedBuf;

    public NxPacketIn() {
        super(NxType.NXT_PACKET_IN);
    }

    public NxPacketIn(int bufferId, short totalFrameLen, PacketInReason reason,
                      byte tableId, long cookie, NxMatch nxm, byte[] packet) {
        super(NxType.NXT_PACKET_IN);
        this.bufferId = bufferId;
        this.totalFrameLen = totalFrameLen;
        this.reason = reason;
        this.tableId = tableId;
        this.cookie = cookie;
        this.nxm = nxm;
        this.packet = packet;
    }

    public int getBufferId() {
        return bufferId;
    }

    public short getTotalFrameLen() {
        return totalFrameLen;
    }

    public PacketInReason getReason() {
        return reason;
    }

    public byte getTableId() {
        return tableId;
    }

    public long getCookie() {
        return cookie;
    }

    public NxMatch getNxMatch() {
        return nxm;
    }

    public byte[] getPacket() {
        return packet;
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        bufferId = data.getInt();
        totalFrameLen = data.getShort();
        reason = PacketInReason.values()[(0xff & data.get())];
        tableId = data.get();
        cookie = data.getLong();
        short nxMatchLen = data.getShort();

        // 6 bytes of padding
        for (int i = 0; i < 6; i++)
            data.get();

        // read NxMatch
        int limit = data.limit();
        data.limit(data.position()+nxMatchLen);
        try {
            nxm = NxMatch.deserialize(data);
        } catch (NxmIOException e) {
            throw new RuntimeException(e);
        }
        data.limit(limit);

        /* read the zero bytes - note the two extra bytes and see
           nicira-ext.h for the definition of nx_packet_in. The packet is
           followed by:
            - Exactly match_len (possibly 0) bytes containing the nx_match, then
            - Exactly (match_len + 7)/8*8 - match_len (between 0 and 7) bytes of
              all-zero bytes, then
            - Exactly 2 all-zero padding bytes, then
            - An Ethernet frame whose length is inferred from nxh.header.length.
        */
        int zeroBytes = (nxMatchLen + 7)/8*8 - nxMatchLen + 2;
        for (int i=0; i<zeroBytes; i++)
            data.get();

        // read packet data
        packet = new byte[data.limit() - data.position()];
        data.get(packet);
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
        // The 2 additional bytes are intentional - see the comment in readFrom.
        int zeroBytes = (nxMatchLen + 7) / 8*8 - nxMatchLen + 2;
        int totalLen =
                MINIMUM_LENGTH + nxMatchLen + zeroBytes + packet.length;
        super.setLength(U16.t(totalLen));
        ByteBuffer data = ByteBuffer.allocate(totalLen);

        // now that we set the length, we can write the header
        super.writeTo(data);
        data.putInt(bufferId);
        data.putShort(totalFrameLen);
        int reasonIdx = 0;
        while (reasonIdx < PacketInReason.values().length &&
                !reason.equals(PacketInReason.values()[reasonIdx]))
            reasonIdx++;
        data.put((byte)reasonIdx);
        data.put(tableId);
        data.putLong(cookie);
        data.putShort((short)nxMatchLen);

        // 6 bytes of padding
        for (int i = 0; i < 6; i++)
            data.put((byte)0);

        if (nxmBuffer != null) {
            data.put(nxmBuffer);
        }

        // put zero bytes for padding
        for (int i=0; i<zeroBytes; i++) {
            data.put((byte) 0);
        }

        if (null != packet)
            data.put(packet);

        data.flip();
        serializedBuf = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxPacketIn that = (NxPacketIn) o;

        if (bufferId != that.bufferId) return false;
        if (cookie != that.cookie) return false;
        if (tableId != that.tableId) return false;
        if (totalFrameLen != that.totalFrameLen) return false;
        if (nxm != null ? !nxm.equals(that.nxm) : that.nxm != null)
            return false;
        if (!Arrays.equals(packet, that.packet)) return false;
        if (reason != that.reason) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + bufferId;
        result = 31 * result + (int) totalFrameLen;
        result = 31 * result + (reason != null ? reason.hashCode() : 0);
        result = 31 * result + (int) tableId;
        result = 31 * result + (int) (cookie ^ (cookie >>> 32));
        result = 31 * result + (nxm != null ? nxm.hashCode() : 0);
        result = 31 * result + (packet != null ? Arrays.hashCode(packet) : 0);
        return result;
    }
}
