/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

public abstract class DtoRouterPort extends DtoPort {
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

    @Override
    public boolean equals(Object other) {

        if (!super.equals(other)) {
            return false;
        }

        DtoRouterPort port = (DtoRouterPort) other;

        if (networkAddress != null ? !networkAddress
                .equals(port.networkAddress) : port.networkAddress != null) {
            return false;
        }

        if (networkLength != port.networkLength) {
            return false;
        }

        if (portAddress != null ? !portAddress.equals(port.portAddress)
                : port.portAddress != null) {
            return false;
        }

        return true;
    }
}
