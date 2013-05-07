/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

public class DtoVlanBridgeTrunkPort extends DtoPort {

    @Override
    public String getType() {
        return PortType.TRUNK_VLAN_BRIDGE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DtoVlanBridgeTrunkPort)) return false;
        return super.equals(o);
    }

}
