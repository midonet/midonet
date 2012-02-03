package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;

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
