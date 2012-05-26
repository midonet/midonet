/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.PortType;

@XmlRootElement
public class DtoMaterializedRouterPort extends DtoRouterPort {
    private String localNetworkAddress = null;
    private int localNetworkLength;
    private URI bgps;
    private URI vpns;

    public String getLocalNetworkAddress() {
        return localNetworkAddress;
    }

    public void setLocalNetworkAddress(String localNetworkAddress) {
        this.localNetworkAddress = localNetworkAddress;
    }

    public int getLocalNetworkLength() {
        return localNetworkLength;
    }

    public void setLocalNetworkLength(int localNetworkLength) {
        this.localNetworkLength = localNetworkLength;
    }

    public URI getBgps() {
        return bgps;
    }

    public void setBgps(URI bgps) {
        this.bgps = bgps;
    }

    public URI getVpns() {
        return vpns;
    }

    public void setVpns(URI vpns) {
        this.vpns = vpns;
    }

    @Override
    public String getType() {
        return PortType.MATERIALIZED_ROUTER;
    }
}
