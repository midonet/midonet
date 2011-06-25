/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.OFSwitchConfig;

public interface ConfigHandler {

    void onConfig(OFSwitchConfig config);

}
