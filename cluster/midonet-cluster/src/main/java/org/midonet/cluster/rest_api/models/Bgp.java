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

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.Bgp.class)
public class Bgp extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "local_as")
    public int localAS;

    @ZoomField(name = "peer_as")
    public int peerAS;

    @ZoomField(name = "peer_address", converter = IPAddressUtil.Converter.class)
    public String peerAddr;

    @ZoomField(name = "port_id", converter = UUIDUtil.Converter.class)
    public UUID portId;

    @JsonIgnore
    @ZoomField(name = "bgp_route_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> adRouteIds;

    public String status;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BGP, id);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS, portId);
    }

    public URI getAdRoutes() {
        return relativeUri(ResourceUris.AD_ROUTES);
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void create(UUID portId) {
        create();
        this.portId = portId;
    }

    @JsonIgnore
    public void update(Bgp from) {
        id = from.id;
        adRouteIds = from.adRouteIds;
    }

}
