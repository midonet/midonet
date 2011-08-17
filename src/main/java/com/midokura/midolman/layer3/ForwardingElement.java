package com.midokura.midolman.layer3;

import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;

public interface ForwardingElement {

    public enum Action {
        BLACKHOLE, NOT_IPV4, NO_ROUTE, FORWARD, REJECT, CONSUMED;
    }

    public class ForwardInfo {
        UUID outPortId;
        int gatewayNwAddr;
        MidoMatch newMatch;
        boolean trackConnection;
    }

    Action process(MidoMatch pktMatch, UUID inPortId, ForwardInfo fwdInfo);
}
