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
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;
import com.sun.jersey.server.impl.model.ResourceUriRules;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.rest_api.ResourceUris;

@XmlRootElement
public class DtoBridge {
    private UUID id;
    private String name;
    private boolean adminStateUp = true;
    private String tenantId;
    private UUID inboundFilterId;
    private UUID outboundFilterId;
    private List<UUID> vxLanPortIds;
    private List<UUID> inboundMirrorIds;
    private List<UUID> outboundMirrorIds;
    private URI inboundFilter;
    private URI outboundFilter;
    private URI vxLanPorts;
    private URI uri;
    private URI ports;
    private URI peerPorts;
    private URI macTable;
    private URI arpTable;
    private URI dhcpSubnets;
    private URI dhcpSubnet6s;
    private String vlanMacTableTemplate;
    private String macPortTemplate;
    private String vlanMacPortTemplate;

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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
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

    public List<UUID> getVxLanPortIds() {
        return this.vxLanPortIds;
    }

    public List<UUID> getInboundMirrorIds() {
        return this.inboundMirrorIds;
    }

    public List<UUID> getOutboundMirrorIds() {
        return this.outboundMirrorIds;
    }

    public void setInboundMirrorIds(List<UUID> mirrors) {
        this.inboundMirrorIds = mirrors;
    }

    public void setOutboundMirrorIds(List<UUID> mirrors) {
        this.outboundMirrorIds = mirrors;
    }

    public void setVxLanPortIds(List<UUID> vxLanPortIds) {
        this.vxLanPortIds = vxLanPortIds;
    }

    public URI getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(URI inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public URI getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(URI outboundFilter) {
        this.outboundFilter = outboundFilter;
    }

    public URI getVxLanPorts() {
        return vxLanPorts;
    }

    public void setVxLanPorts(URI vxLanPorts) {
        this.vxLanPorts = vxLanPorts;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }

    public URI getPeerPorts() {
        return peerPorts;
    }

    public void setPeerPorts(URI peerPorts) {
        this.peerPorts = peerPorts;
    }

    public URI getArpTable() {
        return arpTable;
    }

    public void setArpTable(URI arpTable) {
        this.arpTable = arpTable;
    }

    public URI getMacTable() {
        return macTable;
    }

    @JsonIgnore
    public URI getMacTable(Short vlanId) {
        if (vlanId == null)
            return macTable;
        return UriBuilder.fromUri(getUri())
                         .path(ResourceUris.VLANS)
                         .path(vlanId.toString())
                         .path(ResourceUris.MAC_TABLE)
                         .build();
    }

    public void setMacTable(URI macTable) {
        this.macTable = macTable;
    }

    public URI getDhcpSubnets() {
        return dhcpSubnets;
    }

    public void setDhcpSubnets(URI dhcpSubnets) {
        this.dhcpSubnets = dhcpSubnets;
    }

    public URI getDhcpSubnet6s() {
        return dhcpSubnet6s;
    }

    public void setDhcpSubnet6s(URI dhcpSubnet6s) {
        this.dhcpSubnet6s = dhcpSubnet6s;
    }

    public String getVlanMacTableTemplate() {
        return vlanMacTableTemplate;
    }

    public void setVlanMacTableTemplate(String vlanMacTableTemplate) {
        this.vlanMacTableTemplate = vlanMacTableTemplate;
    }

    public String getMacPortTemplate() {
        return macPortTemplate;
    }

    public void setMacPortTemplate(String macPortTemplate) {
        this.macPortTemplate = macPortTemplate;
    }

    public String getVlanMacPortTemplate() {
        return vlanMacPortTemplate;
    }

    public void setVlanMacPortTemplate(String vlanMacPortTemplate) {
        this.vlanMacPortTemplate = vlanMacPortTemplate;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoBridge that = (DtoBridge) other;
        return Objects.equal(id, that.getId()) &&
               Objects.equal(name, that.getName()) &&
               Objects.equal(tenantId, that.getTenantId()) &&
               Objects.equal(inboundFilterId, that.getInboundFilterId()) &&
               Objects.equal(inboundFilter, that.getInboundFilter()) &&
               Objects.equal(outboundFilterId, that.getOutboundFilterId()) &&
               Objects.equal(outboundFilter, that.getOutboundFilter()) &&
               Objects.equal(vxLanPortIds, that.getVxLanPortIds()) &&
               Objects.equal(vxLanPorts, that.getVxLanPorts()) &&
               Objects.equal(uri, that.getUri()) &&
               Objects.equal(ports, that.getPorts()) &&
               Objects.equal(peerPorts, that.getPeerPorts()) &&
               Objects.equal(macTable, that.getMacTable()) &&
               Objects.equal(arpTable, that.getArpTable()) &&
               Objects.equal(dhcpSubnet6s, that.getDhcpSubnet6s()) &&
               Objects.equal(dhcpSubnets, that.getDhcpSubnets()) &&
               Objects.equal(inboundMirrorIds, that.getInboundMirrorIds()) &&
               Objects.equal(outboundMirrorIds, that.getOutboundMirrorIds()) &&
               adminStateUp == that.adminStateUp;
    }
}
