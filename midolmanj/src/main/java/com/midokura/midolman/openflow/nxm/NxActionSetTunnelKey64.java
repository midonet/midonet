package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxActionSetTunnelKey64 extends NxAction {

    final long key;
    
    protected NxActionSetTunnelKey64(long key) {
        super((short) 9); // NXAST_SET_TUNNEL64
        
        this.key = key;
        
        super.setLength((short) 24);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        // 6 bytes of padding
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        
        data.putLong(key);
    }

}
