/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

public class DtoBridgePort extends DtoPort {

    private Short vlanId;

    @Override
    public Short getVlanId() {
        return this.vlanId;
    }

    public void setVlanId(Short vlanId) {
        this.vlanId = vlanId;
    }

    @Override
    public String getType() {
        return PortType.BRIDGE;
    }
}
