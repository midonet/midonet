package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxSetPacketInFormat extends NxMessage {

    final boolean niciraFormat;
    
    public NxSetPacketInFormat(boolean niciraFormat) {
        super(16); // NXT_SET_PACKET_IN_FORMAT
        
        this.niciraFormat = niciraFormat;
        
        super.setLength((short) 20);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        data.putInt(niciraFormat ? 1 : 0);
    }

}
