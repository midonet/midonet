/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

public class DtoVxLanPort extends DtoBridgePort {

    private String managementIp;
    private int managementPort;
    private int vni;

    @Override
    public Short getVlanId() {
        return null;
    }

    @Override
    public String getType() {
        return PortType.VXLAN;
    }

    public String getManagementIp() {
        return managementIp;
    }

    public void setManagementIp(String managementIp) {
        this.managementIp = managementIp;
    }

    public int getManagementPort() {
        return managementPort;
    }

    public void setManagementPort(int managementPort) {
        this.managementPort = managementPort;
    }

    public int getVni() {
        return vni;
    }

    public void setVni(int vni) {
        this.vni = vni;
    }
}
