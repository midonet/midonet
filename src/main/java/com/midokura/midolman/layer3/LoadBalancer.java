package com.midokura.midolman.layer3;

import com.midokura.midolman.openflow.MidoMatch;

public interface LoadBalancer {

    Route lookup(MidoMatch pktMatch);
}
