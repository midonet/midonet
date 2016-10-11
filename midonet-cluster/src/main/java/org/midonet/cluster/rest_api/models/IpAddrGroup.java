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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.ws.rs.core.UriBuilder;

import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;


@ZoomClass(clazz = Topology.IPAddrGroup.class)
@SuppressWarnings("unused")
public class IpAddrGroup extends UriResource {

    private static final int MIN_IP_ADDR_GROUP_NAME_LEN = 1;
    private static final int MAX_IP_ADDR_GROUP_NAME_LEN = 255;

    @ZoomField(name = "id")
    public UUID id;

    @NotNull
    @Size(min = MIN_IP_ADDR_GROUP_NAME_LEN, max = MAX_IP_ADDR_GROUP_NAME_LEN)
    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "ip_addr_ports")
    public List<IpAddrPort> ipAddrPorts;

    @ZoomField(name = "inbound_chain_id")
    public UUID inboundChainId;

    @ZoomField(name = "outbound_chain_id")
    public UUID outboundChainId;

    @ZoomField(name = "rule_ids")
    public List<UUID> ruleIds;

    public IpAddrGroup() {
    }

    public IpAddrGroup(org.midonet.cluster.data.IpAddrGroup data) {
        this(data.getId(), data.getName());
    }

    public IpAddrGroup(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.IP_ADDR_GROUPS(), id);
    }

    public URI getAddrs() {
        return UriBuilder.fromUri(relativeUri(ResourceUris.IP_ADDRS()))
                         .build();
    }

    @Override
    public void create() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("name", name)
            .toString();
    }
}

