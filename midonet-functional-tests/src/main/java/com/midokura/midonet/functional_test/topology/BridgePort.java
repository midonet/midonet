/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class BridgePort {
    private MidolmanMgmt mgmt;
    private DtoPort port;

    public BridgePort(MidolmanMgmt mgmt, DtoPort port) {
        this.mgmt = mgmt;
        this.port = port;
    }

    public UUID getId()
    {
        return port.getId();
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }
}
