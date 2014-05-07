/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

public class DtoVtepBinding {
    private String portName;
    private short vlanId;
    private UUID networkId;

    private URI uri;

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public short getVlanId() {
        return vlanId;
    }

    public void setVlanId(short vlanId) {
        this.vlanId = vlanId;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    public void setNetworkId(UUID networkId) {
        this.networkId = networkId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoVtepBinding that = (DtoVtepBinding) o;

        return vlanId == that.vlanId &&
                Objects.equals(portName, that.portName) &&
                Objects.equals(networkId, that.networkId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(portName, vlanId, networkId);
    }

    @Override
    public String toString() {
        return "DtoVtepBinding{" +
            "portName='" + portName + '\'' +
            ", vlanId=" + vlanId +
            ", networkId=" + networkId +
            ", uri=" + uri +
            '}';
    }
}
