package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxSetFlowFormat extends NxMessage {

    final boolean nxmFormat;
    
    public NxSetFlowFormat(boolean nxm) {
        super(12); // NXT_SET_FLOW_FORMAT
        
        this.nxmFormat = nxm;
        
        super.setLength((short) 20);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        data.putInt(nxmFormat ? 2 : 0);
    }

}
