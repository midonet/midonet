/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.action.OFActionVendor;

public class MidokuraAction extends OFActionVendor {

    public MidokuraAction() {
        super.setVendor(VendorHandler.MIDOKURA_VENDOR_ID);
    }

}
