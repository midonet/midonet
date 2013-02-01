/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

public class DtoBridgePort extends DtoPort {

    @Override
    public String getType() {
        return PortType.EXTERIOR_BRIDGE;
    }
}
