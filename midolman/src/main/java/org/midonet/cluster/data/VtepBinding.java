/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.util.Objects;
import java.util.UUID;

public class VtepBinding {

    private final String portName;
    private final short vlanId;
    private final UUID networkId;

    public VtepBinding(String portName, short vlanId, UUID networkId) {
        this.portName = portName;
        this.vlanId = vlanId;
        this.networkId = networkId;
    }

    public String getPortName() {
        return portName;
    }

    public short getVlanId() {
        return vlanId;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VtepBinding that = (VtepBinding) o;

        return vlanId == that.vlanId &&
                Objects.equals(networkId, that.networkId) &&
                Objects.equals(portName, that.portName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vlanId, networkId, portName);
    }

    @Override
    public String toString() {
        return "VtepBinding{" +
                "portName='" + portName + '\'' +
                ", vlanId=" + vlanId +
                ", networkId=" + networkId +
                '}';
    }
}
