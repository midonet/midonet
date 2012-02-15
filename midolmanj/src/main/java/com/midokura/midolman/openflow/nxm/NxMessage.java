package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.openflow.protocol.OFVendor;

public abstract class NxMessage extends OFVendor {

    public final static int NX_VENDOR_ID  = 0x00002320;
    
    protected final int subtype;
    
    protected NxMessage(int subtype) {
        super.setVendor(NX_VENDOR_ID);
        
        this.subtype = subtype;
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        data.putInt(subtype);
    }

    @Override
    public String toString() {
        return "NxMessage{" + super.toString() +
                ", subtype=" + subtype +
                '}';
    }
}
