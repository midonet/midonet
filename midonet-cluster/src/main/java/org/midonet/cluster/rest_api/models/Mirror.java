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

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

@ZoomClass(clazz = Topology.Mirror.class)
public class Mirror extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @NotNull
    @ZoomField(name = "to_port_id")
    public UUID toPortId;

    @ZoomField(name = "conditions")
    public List<Condition> conditions;

    @JsonIgnore
    @ZoomField(name = "network_inbound_ids")
    public List<UUID> networkInboundIds;

    @JsonIgnore
    @ZoomField(name = "network_outbound_ids")
    public List<UUID> networkOutboundIds;

    @JsonIgnore
    @ZoomField(name = "router_inbound_ids")
    public List<UUID> routerInboundIds;

    @JsonIgnore
    @ZoomField(name = "router_outbound_ids")
    public List<UUID> routerOutboundIds;

    @JsonIgnore
    @ZoomField(name = "port_inbound_ids")
    public List<UUID> portInboundIds;

    @JsonIgnore
    @ZoomField(name = "port_outbound_ids")
    public List<UUID> portOutboundIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.MIRRORS, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(Mirror from) {
        this.id = from.id;
        networkInboundIds = from.networkInboundIds;
        networkOutboundIds = from.networkOutboundIds;
        routerInboundIds = from.routerInboundIds;
        routerOutboundIds = from.routerOutboundIds;
        portInboundIds = from.portInboundIds;
        portOutboundIds = from.portOutboundIds;
    }
}
