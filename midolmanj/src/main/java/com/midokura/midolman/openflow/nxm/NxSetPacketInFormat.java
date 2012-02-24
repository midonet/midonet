/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxSetPacketInFormat extends NxMessage {

    protected boolean niciraFormat;

    // Should only be used for de-serialization.
    public NxSetPacketInFormat() {
        super(NxType.NXT_SET_PACKET_IN_FORMAT);
    }

    public NxSetPacketInFormat(boolean niciraFormat) {
        super(NxType.NXT_SET_PACKET_IN_FORMAT);
        this.niciraFormat = niciraFormat;
        super.setLength((short) 20);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);

        data.putInt(niciraFormat ? 1 : 0);
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        int format = data.getInt();
        if (format == 1)
            niciraFormat = true;
        else if (format == 0)
            niciraFormat = false;
        else
            throw new RuntimeException("readFrom - format must 1 or 0");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxSetPacketInFormat that = (NxSetPacketInFormat) o;

        if (niciraFormat != that.niciraFormat) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (niciraFormat ? 1 : 0);
        return result;
    }
}
