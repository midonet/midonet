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
package org.midonet.api.network;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.host.validation.IsValidTunnelZoneId;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.midolman.state.VtepConnectionState;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4;

import static scala.collection.JavaConversions.seqAsJavaList;

public class VTEP extends UriResource {

    @NotNull
    @Pattern(regexp = IPv4.regex,
             message = MessageProperty.IP_ADDR_INVALID)
    private String managementIp;

    @Min(1)
    @Max(65535)
    private int managementPort;

    private String name;
    private String description;
    private VtepConnectionState connectionState;

    @IsValidTunnelZoneId
    private UUID tunnelZoneId;

    private List<String> tunnelIpAddrs;

    public VTEP() {}

    public VTEP(org.midonet.cluster.data.VTEP vtep, PhysicalSwitch ps) {
        managementIp = vtep.getId().toString();
        managementPort = vtep.getMgmtPort();
        tunnelZoneId = vtep.getTunnelZoneId();

        if (ps == null) {
            connectionState = VtepConnectionState.ERROR;
        } else {
            connectionState = VtepConnectionState.CONNECTED;
            description = ps.description();
            name = ps.name();
            tunnelIpAddrs = seqAsJavaList(ps.tunnelIpStrings().toList());
        }
    }

    public org.midonet.cluster.data.VTEP toData() {
        return new org.midonet.cluster.data.VTEP()
                .setId(IPv4Addr.fromString(managementIp))
                .setMgmtPort(managementPort)
                .setTunnelZone(tunnelZoneId);
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

    public VtepConnectionState getConnectionState() {
        return connectionState;
    }

    public void setConnectionState(VtepConnectionState connectionState) {
        this.connectionState = connectionState;
    }

    public List<String> getTunnelIpAddrs() {
        return tunnelIpAddrs;
    }

    public void setTunnelIpAddrs(List<String> tunnelIpAddrs) {
        this.tunnelIpAddrs = tunnelIpAddrs;
    }

    public UUID getTunnelZoneId() {
        return this.tunnelZoneId;
    }

    public void setTunnelZoneId(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    public URI getUri() {
        return (getBaseUri() == null || managementIp == null) ? null :
                ResourceUriBuilder.getVtep(getBaseUri(), managementIp);
    }

    public URI getBindings() {
        return (getBaseUri() == null || managementIp == null) ? null :
                ResourceUriBuilder.getVtepBindings(getBaseUri(), managementIp);
    }

    public URI getPorts() {
        return (getBaseUri() == null || managementIp == null) ? null :
                ResourceUriBuilder.getVtepPorts(getBaseUri(), managementIp);
    }

    public String getVtepBindingTemplate() {
        return getBaseUri() == null ? null :
                ResourceUriBuilder.getVtepBindingTemplate(getBaseUri(),
                                                          getManagementIp());
    }
}
