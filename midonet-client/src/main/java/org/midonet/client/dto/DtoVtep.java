/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public class DtoVtep {
    private String managementIp;
    private int managementPort;
    private String name;
    private String description;
    private String connectionState;
    private List<String> tunnelIpAddrs;
    private URI uri;
    private URI bindings;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getConnectionState() {
        return connectionState;
    }

    public void setConnectionState(String connectionState) {
        this.connectionState = connectionState;
    }

    public List<String> getTunnelIpAddrs() {
        return tunnelIpAddrs;
    }

    public void setTunnelIpAddrs(List<String> tunnelIpAddrs) {
        this.tunnelIpAddrs = tunnelIpAddrs;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getBindings() {
        return bindings;
    }

    public void setBindings(URI bindings) {
        this.bindings = bindings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoVtep dtoVtep = (DtoVtep) o;
        return managementPort == dtoVtep.managementPort &&
                Objects.equals(connectionState, dtoVtep.connectionState) &&
                Objects.equals(description, dtoVtep.description) &&
                Objects.equals(managementIp, dtoVtep.managementIp) &&
                Objects.equals(name, dtoVtep.name) &&
                Objects.equals(tunnelIpAddrs, dtoVtep.tunnelIpAddrs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(managementIp, managementPort, name, description,
                            connectionState, tunnelIpAddrs);
    }
}
