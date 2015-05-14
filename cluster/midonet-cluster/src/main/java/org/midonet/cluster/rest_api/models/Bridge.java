/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.util.collection.ListUtil;
import org.midonet.util.version.Since;

@ZoomClass(clazz = Topology.Network.class)
public class Bridge extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    private UUID id;

    @ZoomField(name = "tenant_id")
    @NotNull
    private String tenantId;

    @ZoomField(name = "name")
    @NotNull
    private String name;

    @ZoomField(name = "admin_state_up")
    private boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = Converter.class)
    private UUID inboundFilterId;

    @ZoomField(name = "outbound_filter_id", converter = Converter.class)
    private UUID outboundFilterId;

    // TODO: validation, this field must not be updated by the user
    @Since("2")
    private UUID vxLanPortId;

    // TODO: validation, this field must not be updated by the user
    @ZoomField(name = "vxlan_port_ids", converter = Converter.class)
    @Since("3") // after adding support to multiple vtep bindings
    private List<UUID> vxLanPortIds;

    @JsonIgnore
    @ZoomField(name = "port_ids", converter = Converter.class)
    private List<UUID> portIds;

    @JsonIgnore
    @ZoomField(name = "dhcp_ids", converter = Converter.class)
    private List<UUID> dhcpIds;

    public Bridge() {
        adminStateUp = true;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BRIDGES, id);
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public UUID getInboundFilterId() {
        return inboundFilterId;
    }

    public void setInboundFilterId(UUID inboundFilterId) {
        this.inboundFilterId = inboundFilterId;
    }

    public UUID getOutboundFilterId() {
        return outboundFilterId;
    }

    public void setOutboundFilterId(UUID outboundFilterId) {
        this.outboundFilterId = outboundFilterId;
    }

    public UUID getVxLanPortId() {
        return vxLanPortId;
    }

    public void setVxLanPortId(UUID vxLanPortId) {
        this.vxLanPortId = vxLanPortId;
    }

    public List<UUID> getVxLanPortIds() {
        return vxLanPortIds;
    }

    public void setVxLanPortIds(List<UUID> vxLanPortIds) {
        this.vxLanPortIds = vxLanPortIds;
    }

    public List<UUID> getPortIds() {
        return portIds;
    }

    public void setPortIds(List<UUID> portIds) {
        this.portIds = portIds;
    }

    public List<UUID> getDhcpIds() {
        return dhcpIds;
    }

    public void setDhcpIds(List<UUID> dhcpIds) {
        this.dhcpIds = dhcpIds;
    }

    public URI getInboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, inboundFilterId);
    }

    public URI getOutboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, outboundFilterId);
    }

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }

    public URI getPeerPorts() {
        return relativeUri(ResourceUris.PEER_PORTS);
    }

    @Since("3")
    public List<URI> getVxLanPorts() {
        return absoluteUris(ResourceUris.VXLAN_PORTS, vxLanPortIds);
    }

    @Since("2")
    public URI getVxLanPort() {
        return absoluteUri(ResourceUris.PORTS, vxLanPortId);
    }

    public URI getMacTable() {
        return relativeUri(ResourceUris.MAC_TABLE);
    }

    public URI getArpTable() {
        return relativeUri(ResourceUris.ARP_TABLE);
    }

    public URI getDhcpSubnets() {
        return relativeUri(ResourceUris.DHCP);
    }

    public URI getDhcpSubnet6s() {
        return relativeUri(ResourceUris.DHCPV6);
    }

    public String getVlanMacTableTemplate() {
        return getUri() + "/vlans/{vlanId}/mac_table";
    }

    public String getMacPortTemplate() {
        return getUri() + "/mac_table/{macAddress}_{portId}";
    }

    public String getVlanMacPortTemplate() {
        return getUri() + "/vlans/{vlanId}/mac_table/{macAddress}_{portId}";
    }

    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    public void update(UUID id, Bridge from) {
        this.id = id;
        portIds = from.portIds;
        vxLanPortIds = from.vxLanPortIds;
        dhcpIds = from.dhcpIds;
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Bridge)) return false;
        final Bridge other = (Bridge) obj;

        return super.equals(other)
               && Objects.equal(id, other.id)
               && Objects.equal(name, other.name)
               && Objects.equal(inboundFilterId, other.inboundFilterId)
               && Objects.equal(outboundFilterId, other.outboundFilterId)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(adminStateUp, other.adminStateUp)
               && Objects.equal(vxLanPortId, other.vxLanPortId)
               && ListUtils.isEqualList(portIds, other.portIds)
               && ListUtils.isEqualList(dhcpIds, other.dhcpIds);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(),
                                id, name, tenantId,
                                inboundFilterId, outboundFilterId,
                                adminStateUp, vxLanPortId,
                                ListUtils.hashCodeForList(portIds),
                                ListUtils.hashCodeForList(dhcpIds));
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("URI", super.toString())
            .add("id", id)
            .add("name", name)
            .add("tenantId", tenantId)
            .add("inboundFilterId", inboundFilterId)
            .add("outboundFilterId", outboundFilterId)
            .add("adminStateUp", adminStateUp)
            .add("vxLanPortId", vxLanPortId)
            .add("portIds", ListUtil.toString(portIds))
            .add("dhcpIds", ListUtil.toString(dhcpIds))
            .toString();
    }

    public static class BridgeData extends Bridge {

        private URI arpTable;
        private URI dhcpSubnets;
        private URI dhcpSubnet6s;
        private URI inboundFilter;
        private String macPortTemplate;
        private URI macTable;
        private URI outboundFilter;
        private URI peerPorts;
        private URI ports;
        private URI uri;
        private String vlanMacPortTemplate;
        private String vlanMacTableTemplate;
        @Since("2")
        private URI vxLanPort;
        @Since("3")
        private List<URI> vxLanPorts;

        @Override
        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        @Override
        public URI getInboundFilter() {
            return inboundFilter;
        }

        public void setInboundFilter(URI inboundFilter) {
            this.inboundFilter = inboundFilter;
        }

        @Override
        public URI getOutboundFilter() {
            return outboundFilter;
        }

        public void setOutboundFilter(URI outboundFilter) {
            this.outboundFilter = outboundFilter;
        }

        @Override
        public URI getDhcpSubnets() {
            return dhcpSubnets;
        }

        public void setDhcpSubnets(URI dhcpSubnets) {
            this.dhcpSubnets = dhcpSubnets;
        }

        @Override
        public URI getArpTable() {
            return arpTable;
        }

        public void setArpTable(URI arpTable) {
            this.arpTable = arpTable;
        }

        @Override
        public URI getDhcpSubnet6s() {
            return dhcpSubnet6s;
        }

        public void setDhcpSubnet6s(URI dhcpSubnet6s) {
            this.dhcpSubnet6s = dhcpSubnet6s;
        }

        @Override
        public String getMacPortTemplate() {
            return macPortTemplate;
        }

        public void setMacPortTemplate(String macPortTemplate) {
            this.macPortTemplate = macPortTemplate;
        }

        @Override
        public URI getMacTable() {
            return macTable;
        }

        public void setMacTable(URI macTable) {
            this.macTable = macTable;
        }

        @Override
        public URI getPeerPorts() {
            return peerPorts;
        }

        public void setPeerPorts(URI peerPorts) {
            this.peerPorts = peerPorts;
        }

        @Override
        public URI getPorts() {
            return ports;
        }

        public void setPorts(URI ports) {
            this.ports = ports;
        }

        @Override
        public String getVlanMacPortTemplate() {
            return vlanMacPortTemplate;
        }

        public void setVlanMacPortTemplate(String vlanMacPortTemplate) {
            this.vlanMacPortTemplate = vlanMacPortTemplate;
        }

        @Override
        public String getVlanMacTableTemplate() {
            return vlanMacTableTemplate;
        }

        public void setVlanMacTableTemplate(String vlanMacTableTemplate) {
            this.vlanMacTableTemplate = vlanMacTableTemplate;
        }

        @Override
        public URI getVxLanPort() {
            return vxLanPort;
        }

        public void setVxLanPort(URI vxLanPort) {
            this.vxLanPort = vxLanPort;
        }

        @Override
        public List<URI> getVxLanPorts() {
            return vxLanPorts;
        }

        public void setVxLanPorts(List<URI> vxLanPorts) {
            this.vxLanPorts = vxLanPorts;
        }

        @Override
        public boolean equals(Object obj) {

            if (obj == this) return true;

            if (!(obj instanceof BridgeData)) return false;
            final BridgeData other = (BridgeData) obj;

            return super.equals(other)
                   && Objects.equal(uri, other.uri)
                   && Objects.equal(vlanMacTableTemplate,
                                    other.vlanMacTableTemplate)
                   && Objects.equal(macPortTemplate, other.macPortTemplate)
                   && Objects.equal(vlanMacPortTemplate,
                                    other.vlanMacPortTemplate)
                   && Objects.equal(ports, other.ports)
                   && Objects.equal(peerPorts, other.peerPorts)
                   && Objects.equal(vxLanPorts, other.vxLanPorts)
                   && Objects.equal(vxLanPort, other.vxLanPort)
                   && Objects.equal(macTable, other.macTable)
                   && Objects.equal(arpTable, other.arpTable)
                   && Objects.equal(dhcpSubnets, other.dhcpSubnets)
                   && Objects.equal(dhcpSubnet6s, other.dhcpSubnet6s);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), uri,
                                    vlanMacTableTemplate, macPortTemplate,
                                    vlanMacPortTemplate, ports, peerPorts,
                                    vxLanPorts, vxLanPort,
                                    macTable, arpTable,
                                    dhcpSubnets, dhcpSubnet6s);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("Bridge", super.toString())
                .add("uri", uri)
                .add("vlanMacTableTemplate", vlanMacTableTemplate)
                .add("macPortTemplate", macPortTemplate)
                .add("vlanMacPortTemplate", vlanMacPortTemplate)
                .add("ports", ports)
                .add("peerPorts", peerPorts)
                .add("vxLanPorts", vxLanPorts)
                .add("vxLanPort", vxLanPort)
                .add("macTable", macTable)
                .add("arpTable", arpTable)
                .add("dhcpSubnets", dhcpSubnets)
                .add("dhcpSubnet6s", dhcpSubnet6s)
                .toString();
        }
    }

}
