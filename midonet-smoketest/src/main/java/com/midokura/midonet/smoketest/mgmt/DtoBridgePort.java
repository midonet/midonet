package com.midokura.midonet.smoketest.mgmt;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Copyright 2011 Midokura Europe SARL
 * User: rossella rossella@midokura.com
 * Date: 12/8/11
 * Time: 2:59 PM
 */
@XmlRootElement
public class DtoBridgePort {

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
