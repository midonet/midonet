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
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.util.collection.ListUtil;
import org.midonet.util.version.Since;

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

    @JsonIgnore
    @Subresource(name = ResourceUris.PORTS)
    @ZoomField(name = "port_ids", converter = Converter.class)
    public List<UUID> portIds;

    @JsonIgnore
    @Subresource(name = ResourceUris.DHCP)
    @ZoomField(name = "dhcp_ids", converter = Converter.class)
    public List<UUID> dhcpIds;

    private String vlanMacTableTemplate;

    private String macPortTemplate;

    private String vlanMacPortTemplate;

    private URI ports;

    private URI peerPorts;

    @Since("3")
    private List<URI> vxLanPorts;

    @Since("2")
    private URI vxLanPort;

    private URI macTable;

    private URI arpTable;

    private URI dhcpSubnets;

    private URI dhcpSubnet6s;

    public Bridge() {
        this(null);
    }

    public Bridge(URI baseUri) {
        super(baseUri);
        this.adminStateUp = true;
    }

    public void setVlanMacTableTemplate(String template) {
        this.vlanMacTableTemplate = template;
    }

    public String getVlanMacTableTemplate() {
        return getUriTemplateFor("/vlans/{vlanId}/mac_table",
                                 this.vlanMacTableTemplate);
    }

    public void setMacPortTemplate(String template) {
        this.macPortTemplate = template;
    }

    public String getMacPortTemplate() {
        return getUriTemplateFor("/mac_table/{macAddress}_{portId}",
                                 macPortTemplate);
    }

    public void setVlanMacPortTemplate(String template) {
        this.vlanMacPortTemplate = template;
    }

    public String getVlanMacPortTemplate() {
        return getUriTemplateFor(
            "/vlans/{vlanId}/mac_table/{macAddress}_{portId}",
            this.vlanMacPortTemplate);
    }

    public void setPorts(URI uri) {
        this.ports = uri;
    }

    public URI getPorts() {
        return getUriFor(ResourceUris.PORTS, this.ports);
    }

    public void setPeerPorts(URI uri) {
        this.peerPorts = uri;
    }

    public URI getPeerPorts() {
        return getUriFor(ResourceUris.PEER_PORTS, this.peerPorts);
    }

    public void setMacTable(URI uri) {
        this.macTable = uri;
    }

    public URI getMacTable() {
        return getUriFor(ResourceUris.MAC_TABLE, this.macTable);
    }

    public void setArpTable(URI uri) {
        this.arpTable = uri;
    }

    public URI getArpTable() {
        return getUriFor(ResourceUris.ARP_TABLE, this.arpTable);
    }

    public void setDhcpSubnets(URI uri) {
        this.dhcpSubnets = uri;
    }

    public URI getDhcpSubnets() {
        return getUriFor(ResourceUris.DHCP, this.dhcpSubnets);
    }

    public void setDhcpSubnet6s(URI uri) {
        this.dhcpSubnet6s = uri;
    }

    public URI getDhcpSubnet6s() {
        return getUriFor(ResourceUris.DHCPV6, this.dhcpSubnet6s);
    }

    public void setVxLanPorts(List<URI> uri) {
        this.vxLanPorts = uri;
    }

    public List<URI> getVxLanPorts() {
        return getUrisFor(ResourceUris.VXLAN_PORTS, vxLanPortIds,
                          this.vxLanPorts);
    }

    public void setVxLanPort(URI uri) {
        this.vxLanPort = uri;
    }

    public URI getVxLanPort() {
        return getUriFor(ResourceUris.PORTS, vxLanPortId, this.vxLanPort);
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
