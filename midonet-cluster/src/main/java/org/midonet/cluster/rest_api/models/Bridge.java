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

import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil.Converter;

import static org.midonet.cluster.rest_api.ResourceUris.ARP_TABLE;
import static org.midonet.cluster.rest_api.ResourceUris.BRIDGES;
import static org.midonet.cluster.rest_api.ResourceUris.CHAINS;
import static org.midonet.cluster.rest_api.ResourceUris.DHCP;
import static org.midonet.cluster.rest_api.ResourceUris.DHCPV6;
import static org.midonet.cluster.rest_api.ResourceUris.MAC_TABLE;
import static org.midonet.cluster.rest_api.ResourceUris.PEER_PORTS;
import static org.midonet.cluster.rest_api.ResourceUris.PORTS;
import static org.midonet.cluster.rest_api.ResourceUris.VXLAN_PORTS;

@ZoomClass(clazz = Topology.Network.class)
public class Bridge extends UriResource {

    public static final int MIN_BRIDGE_NAME_LEN = 0;
    public static final int MAX_BRIDGE_NAME_LEN = 255;

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @Size(min = MIN_BRIDGE_NAME_LEN, max = MAX_BRIDGE_NAME_LEN)
    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = Converter.class)
    public UUID inboundFilterId;

    @ZoomField(name = "outbound_filter_id", converter = Converter.class)
    public UUID outboundFilterId;

    @ZoomField(name = "vxlan_port_ids", converter = Converter.class)
    public List<UUID> vxLanPortIds;

    @JsonIgnore
    @ZoomField(name = "port_ids", converter = Converter.class)
    public List<UUID> portIds;

    @JsonIgnore
    @ZoomField(name = "dhcp_ids", converter = Converter.class)
    public List<UUID> dhcpIds;

    @JsonIgnore
    @ZoomField(name = "dhcpv6_ids", converter = Converter.class)
    public List<UUID> dhcpv6Ids;

    @JsonIgnore
    @ZoomField(name = "trace_request_ids", converter = Converter.class)
    public List<UUID> traceRequestIds;

    @ZoomField(name = "inbound_mirror_ids", converter = Converter.class)
    public List<UUID> inboundMirrorIds;

    @ZoomField(name = "outbound_mirror_ids", converter = Converter.class)
    public List<UUID> outboundMirrorIds;

    public Bridge() {
        adminStateUp = true;
    }

    @Override
    public URI getUri() {
        return absoluteUri(BRIDGES, id);
    }

    public URI getInboundFilter() {
        return absoluteUri(CHAINS, inboundFilterId);
    }

    public URI getOutboundFilter() {
        return absoluteUri(CHAINS, outboundFilterId);
    }

    public URI getPorts() {
        return relativeUri(PORTS);
    }

    public URI getPeerPorts() {
        return relativeUri(PEER_PORTS);
    }

    public URI getVxLanPorts() {
        return relativeUri(VXLAN_PORTS);
    }

    public URI getMacTable() {
        return relativeUri(MAC_TABLE);
    }

    public URI getArpTable() {
        return relativeUri(ARP_TABLE);
    }

    public URI getDhcpSubnets() {
        return relativeUri(DHCP);
    }

    public URI getDhcpSubnet6s() {
        return relativeUri(DHCPV6);
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

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        vxLanPortIds = null;
    }

    @JsonIgnore
    public void update(Bridge from) {
        id = from.id;
        portIds = from.portIds;
        vxLanPortIds = from.vxLanPortIds;
        dhcpIds = from.dhcpIds;
        traceRequestIds = from.traceRequestIds;
    }
}
