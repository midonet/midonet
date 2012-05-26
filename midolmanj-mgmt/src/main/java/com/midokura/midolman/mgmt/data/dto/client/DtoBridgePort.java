/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import com.midokura.midolman.mgmt.data.dto.PortType;

public class DtoBridgePort extends DtoPort {

    @Override
    public String getType() {
        return PortType.MATERIALIZED_BRIDGE;
    }
}
