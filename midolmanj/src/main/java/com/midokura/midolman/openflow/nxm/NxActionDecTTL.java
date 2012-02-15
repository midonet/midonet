package com.midokura.midolman.openflow.nxm;

public class NxActionDecTTL extends NxAction {
    
    protected NxActionDecTTL() {
        super((short) 18); // NXAST_DEC_TTL
        
        super.setLength((short) 16);
    }

}
