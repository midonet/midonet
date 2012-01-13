package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.mgmt.DtoBridge;
import com.midokura.midonet.smoketest.mgmt.DtoBridgePort;
import com.midokura.midonet.smoketest.mgmt.DtoPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

import java.util.UUID;

/**
 * Copyright 2011 Midokura Europe SARL
 * User: rossella rossella@midokura.com
 * Date: 12/8/11
 * Time: 2:49 PM
 */
public class BridgePort {
        private DtoBridge bridge;
        private DtoPort port;


    public BridgePort(DtoBridge bridge, DtoPort port) {
        this.port = port;
        this.bridge = bridge;
        // port.setNetworkLength(24);
    }

    public UUID getId()
    {
        return port.getId();
    }
}
