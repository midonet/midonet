package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.openflow.protocol.action.OFActionVendor;

public class NxAction extends OFActionVendor {

    public final static int NX_VENDOR_ID  = 0x00002320;
    
    protected final short subtype;
    
    protected NxAction(short subtype) {
        super.setVendor(NX_VENDOR_ID);
        
        this.subtype = subtype;
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        data.putShort(subtype);
    }
}
