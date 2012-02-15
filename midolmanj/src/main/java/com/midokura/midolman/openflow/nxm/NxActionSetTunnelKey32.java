package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxActionSetTunnelKey32 extends NxAction {

    final int key;
    
    public NxActionSetTunnelKey32(int key) {
        super((short) 2); // NXAST_SET_TUNNEL
        
        this.key = key;
        
        super.setLength((short) 16);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        // 2 bytes of padding
        data.put((byte) 0);
        data.put((byte) 0);
        
        data.putInt(key);
    }

}
