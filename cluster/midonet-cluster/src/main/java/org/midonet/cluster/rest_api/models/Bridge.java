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

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.util.version.Since;

@XmlRootElement(name = "bridge")
@Resource(name = ResourceUris.BRIDGES)
@ZoomClass(clazz = Topology.Network.class)
public class Bridge extends UriResource {

    @ResourceId
    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "admin_state_up")
    protected boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = Converter.class)
    public UUID inboundFilterId;
    @ZoomField(name = "outbound_filter_id", converter = Converter.class)
    public UUID outboundFilterId;

    public UUID vxLanPortId;

    @ZoomField(name = "vxlan_port_ids", converter = Converter.class)
    public List<UUID> vxLanPortIds;

    @XmlTransient
    @Subresource(name = ResourceUris.PORTS)
    @ZoomField(name = "port_ids", converter = Converter.class)
    public List<UUID> portIds;

    @XmlTransient
    @Subresource(name = ResourceUris.DHCP)
    @ZoomField(name = "dhcp_ids", converter = Converter.class)
    public List<UUID> dhcpIds;

    public Bridge() {
        adminStateUp = true;
    }

    public String getVlanMacTableTemplate() {
        return getUri() + "/vlans/{vlanId}/mac_table/{macAddress}_{portId}";
    }

    public String getMacPortTemplate() {
        return getUri() + "/mac_table/{macAddress}_{portId}";
    }

    public String getVlanMacPortTemplate() {
        return getUri() + "/vlans/{vlanId}/mac_table/{macAddress}_{portId}";
    }

    public URI getPorts() {
        return getUriFor(ResourceUris.PORTS);
    }

    public URI getPeerPorts() {
        return getUriFor(ResourceUris.PEER_PORTS);
    }

    public List<URI> getVxLanPorts() {
        return getUrisFor(ResourceUris.VXLAN_PORTS, vxLanPortIds);
    }

    public URI getMacTable() {
        return getUriFor(ResourceUris.MAC_TABLE);
    }

    public URI getArpTable() {
        return getUriFor(ResourceUris.ARP_TABLE);
    }

    public URI getDhcpSubnets() {
        return getUriFor(ResourceUris.DHCP);
    }

    public URI getDhcpSubnet6s() {
        return getUriFor(ResourceUris.DHCPV6);
    }

}
