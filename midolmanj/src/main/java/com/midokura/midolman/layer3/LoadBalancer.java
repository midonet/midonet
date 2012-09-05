/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.layer3;

import com.midokura.sdn.flows.PacketMatch;


public interface LoadBalancer {
    Route lookup(PacketMatch pktMatch);
}
