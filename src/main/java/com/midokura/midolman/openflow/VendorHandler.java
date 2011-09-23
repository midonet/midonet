/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

public interface VendorHandler {

    final int MIDOKURA_VENDOR_ID = 0x00ACCABA;
    
    void onVendorMessage(int xid, byte[] data);
    
}
