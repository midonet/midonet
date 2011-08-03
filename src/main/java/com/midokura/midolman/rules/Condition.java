package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;

public class Condition {

    public boolean matches(UUID inPortId, UUID outPortId, MidoMatch pktMatch) {
        return false;
    }
}
