/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxActionSetTunnelKey32 extends NxAction {

    protected int key;

    // Required for deserialization e.g. in NxFlowMod.readFrom
    public NxActionSetTunnelKey32() {
    }

    public NxActionSetTunnelKey32(int key) {
        super(NxActionType.NXAST_SET_TUNNEL);
        this.key = key;
        super.setLength((short) 16);
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        // 2 bytes of padding
        data.get();
        data.get();
        key = data.getInt();
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);

        // 2 bytes of padding
        data.put((byte) 0);
        data.put((byte) 0);

        data.putInt(key);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxActionSetTunnelKey32 that = (NxActionSetTunnelKey32) o;

        if (key != that.key) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + key;
        return result;
    }
}
