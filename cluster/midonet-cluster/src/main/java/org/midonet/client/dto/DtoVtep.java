/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.client.dto;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoVtep {
    private UUID id;
    private String managementIp;
    private int managementPort;
    private String name;
    private String description;
    private String connectionState;
    private UUID tunnelZoneId;
    private List<String> tunnelIpAddrs;
    private URI uri;
    private URI bindings;
    private URI ports;
    private String vtepBindingTemplate;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public void setTunnelZoneId(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    public UUID getTunnelZoneId() {
        return this.tunnelZoneId;
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

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoVtep dtoVtep = (DtoVtep) o;
        return Objects.equals(id, dtoVtep.id) &&
               managementPort == dtoVtep.managementPort &&
                Objects.equals(connectionState, dtoVtep.connectionState) &&
                Objects.equals(description, dtoVtep.description) &&
                Objects.equals(managementIp, dtoVtep.managementIp) &&
                Objects.equals(name, dtoVtep.name) &&
                Objects.equals(tunnelZoneId, dtoVtep.tunnelZoneId) &&
                Objects.equals(tunnelIpAddrs, dtoVtep.tunnelIpAddrs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(managementIp, managementPort, name, tunnelZoneId,
                            description, connectionState, tunnelIpAddrs);
    }

    public String getVtepBindingTemplate() {
        return vtepBindingTemplate;
    }

    public void setVtepBindingTemplate(String vtepBindingTemplate) {
        this.vtepBindingTemplate = vtepBindingTemplate;
    }
}
