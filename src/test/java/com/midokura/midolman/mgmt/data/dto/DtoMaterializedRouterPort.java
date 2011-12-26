/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoMaterializedRouterPort extends DtoRouterPort {
    private String localNetworkAddress = null;
    private int localNetworkLength;
    private URI uri;
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

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
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

}
