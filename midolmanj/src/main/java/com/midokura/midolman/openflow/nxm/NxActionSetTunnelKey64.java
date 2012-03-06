/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxActionSetTunnelKey64 extends NxAction {

    protected long key;

    // Required for deserialization e.g. in NxFlowMod.readFrom
    public NxActionSetTunnelKey64() {
    }

    protected NxActionSetTunnelKey64(long key) {
        super(NxActionType.NXAST_SET_TUNNEL64);
        this.key = key;
        super.setLength((short) 24);
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        // 6 bytes of padding
        for (int i=0; i<6; i++)
            data.get();
        key = data.getLong();
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        // 6 bytes of padding
        for (int i=0; i<6; i++)
            data.put((byte) 0);
        data.putLong(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxActionSetTunnelKey64 that = (NxActionSetTunnelKey64) o;

        if (key != that.key) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) (key ^ (key >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "NxActionSetTunnelKey64{" +
                "key=" + key +
                '}';
    }
}
