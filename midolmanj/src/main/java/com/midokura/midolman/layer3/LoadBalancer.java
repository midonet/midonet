/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.layer3;

import com.midokura.midolman.openflow.MidoMatch;

public interface LoadBalancer {

    Route lookup(MidoMatch pktMatch);
}
