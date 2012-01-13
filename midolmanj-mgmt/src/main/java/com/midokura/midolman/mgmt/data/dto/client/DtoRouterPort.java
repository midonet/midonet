/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoRouterPort extends DtoPort {
    private String networkAddress = null;
    private int networkLength;
    private String portAddress = null;

    public String getNetworkAddress() {
        return networkAddress;
    }

    public void setNetworkAddress(String networkAddress) {
        this.networkAddress = networkAddress;
    }

    public int getNetworkLength() {
        return networkLength;
    }

    public void setNetworkLength(int networkLength) {
        this.networkLength = networkLength;
    }

    public String getPortAddress() {
        return portAddress;
    }

    public void setPortAddress(String portAddress) {
        this.portAddress = portAddress;
    }

}
