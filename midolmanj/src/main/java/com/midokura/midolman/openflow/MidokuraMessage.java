/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.OFVendor;

public class MidokuraMessage extends OFVendor {
    
    public static final int MIDOKURA_VENDOR_ID = 0x00ACCABA;
    
    public MidokuraMessage() {
        super.setVendor(MIDOKURA_VENDOR_ID);
    }
}
