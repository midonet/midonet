/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

public class VTEPBinding extends UriResource {

    // Not included in DTO. Used only to generate URI.
    private String mgmtIp;

    @NotNull
    private String portName;

    @Min(0)
    @Max(4095)
    private short vlanId;

    @NotNull
    private UUID networkId;

    public VTEPBinding() {}

    public VTEPBinding(String mgmtIp, String portName,
                       short vlanId, UUID networkId) {
        setMgmtIp(mgmtIp);
        setPortName(portName);
        setVlanId(vlanId);
        setNetworkId(networkId);
    }

    public String getMgmtIp() {
        return mgmtIp;
    }

    public void setMgmtIp(String mgmtIp) {
        this.mgmtIp = mgmtIp;
    }

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
        return (getBaseUri() == null || mgmtIp == null) ? null :
                ResourceUriBuilder.getVtepBinding(
                        getBaseUri(), mgmtIp, portName, vlanId);
    }

    @Override
    public String toString() {
        return "VtepBinding{" +
            "mgmtIp='" + mgmtIp + '\'' +
            ", portName='" + portName + '\'' +
            ", vlanId=" + vlanId +
            ", networkId=" + networkId +
            '}';
    }
}
