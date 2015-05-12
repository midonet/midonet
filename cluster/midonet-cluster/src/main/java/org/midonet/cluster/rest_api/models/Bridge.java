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
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.util.collection.ListUtil;
import org.midonet.util.version.Since;

@XmlRootElement(name = "bridge")
@Resource(name = ResourceUris.BRIDGES)
@ZoomClass(clazz = Topology.Network.class)
public class Bridge extends UriResource {

    @ResourceId
    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    @NotNull
    public String tenantId;

    @ZoomField(name = "name")
    @NotNull
    public String name;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = Converter.class)
    public UUID inboundFilterId;
    @ZoomField(name = "outbound_filter_id", converter = Converter.class)
    public UUID outboundFilterId;

    // TODO: validation, this field must not be updated by the user
    @Since("2")
    public UUID vxLanPortId;

    // TODO: validation, this field must not be updated by the user
    @ZoomField(name = "vxlan_port_ids", converter = Converter.class)
    @Since("3") // after adding support to multiple vtep bindings
    public List<UUID> vxLanPortIds;

    @XmlTransient
    @Subresource(name = ResourceUris.PORTS)
    @ZoomField(name = "port_ids", converter = Converter.class)
    public List<UUID> portIds;

    @XmlTransient
    @Subresource(name = ResourceUris.DHCP)
    @ZoomField(name = "dhcp_ids", converter = Converter.class)
    public List<UUID> dhcpIds;

    public String vlanMacTableTemplate;

    public String macPortTemplate;

    public String vlanMacPortTemplate;

    public URI ports;

    public URI peerPorts;

    @Since("3")
    public List<URI> vxLanPorts;

    @Since("2")
    public URI vxLanPort;

    public URI macTable;

    public URI arpTable;

    public URI dhcpSubnets;

    public URI dhcpSubnet6s;

    public Bridge() {
        adminStateUp = true;
    }

    @XmlTransient
    @Override
    public void setBaseUri(URI baseUri) throws IllegalAccessException {
        super.setBaseUri(baseUri);
        this.vlanMacTableTemplate = getUriTemplateFor(
            "/vlans/{vlanId}/mac_table");
        this.macPortTemplate = getUriTemplateFor(
            "/mac_table/{macAddress}_{portId}");
        this.vlanMacPortTemplate = getUriTemplateFor(
            "/vlans/{vlanId}/mac_table/{macAddress}_{portId}");
        this.ports = getUriFor(ResourceUris.PORTS);
        this.peerPorts = getUriFor(ResourceUris.PEER_PORTS);
        this.vxLanPorts = getUrisFor(ResourceUris.VXLAN_PORTS, vxLanPortIds);
        this.vxLanPort = getUriFor(ResourceUris.PORTS, vxLanPortId);
        this.macTable = getUriFor(ResourceUris.MAC_TABLE);
        this.arpTable = getUriFor(ResourceUris.ARP_TABLE);
        this.dhcpSubnets = getUriFor(ResourceUris.DHCP);
        this.dhcpSubnet6s = getUriFor(ResourceUris.DHCPV6);
    }

    @XmlTransient
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
               && Objects.equal(vlanMacTableTemplate,
                                other.vlanMacTableTemplate)
               && Objects.equal(macPortTemplate, other.macPortTemplate)
               && Objects.equal(vlanMacPortTemplate, other.vlanMacPortTemplate)
               && Objects.equal(ports, other.ports)
               && Objects.equal(peerPorts, other.peerPorts)
               && Objects.equal(vxLanPorts, other.vxLanPorts)
               && Objects.equal(vxLanPort, other.vxLanPort)
               && Objects.equal(macTable, other.macTable)
               && Objects.equal(arpTable, other.arpTable)
               && Objects.equal(dhcpSubnets, other.dhcpSubnets)
               && Objects.equal(dhcpSubnet6s, other.dhcpSubnet6s)
               && ListUtils.isEqualList(portIds, other.portIds)
               && ListUtils.isEqualList(dhcpIds, other.dhcpIds);
    }

    @XmlTransient
    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(),
                                id, name, tenantId,
                                inboundFilterId, outboundFilterId,
                                adminStateUp, vxLanPortId,
                                vlanMacTableTemplate, macPortTemplate,
                                vlanMacPortTemplate, ports, peerPorts,
                                vxLanPorts, vxLanPort,
                                macTable, arpTable,
                                dhcpSubnets, dhcpSubnet6s,
                                ListUtils.hashCodeForList(portIds),
                                ListUtils.hashCodeForList(dhcpIds));
    }

    @XmlTransient
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
            .add("portIds", ListUtil.toString(portIds))
            .add("dhcpIds", ListUtil.toString(dhcpIds))
            .toString();
    }
}
