/*
<<<<<<< HEAD
 * Copyright 2015 Midokura SARL
=======
 * Copyright 2014 Midokura SARL
>>>>>>> Unify BGP and AdRoute DTOs into cluster
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
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;

@ZoomClass(clazz = Topology.BgpRoute.class)
public class AdRoute extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @JsonIgnore
    @ZoomField(name = "subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> subnet;

    public String nwPrefix;

    public byte prefixLength;

    @ZoomField(name = "bgp_id", converter = UUIDUtil.Converter.class)
    public UUID bgpId = null;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.AD_ROUTES, id);
    }

    public URI getBgp() {
        return absoluteUri(ResourceUris.BGP, bgpId);
    }

    @JsonIgnore
    public void afterFromProto(Message proto) {
        nwPrefix = subnet != null ? subnet.getAddress().toString() : null;
        prefixLength = subnet != null ? (byte)subnet.getPrefixLen() : 0;
    }

    @Override
    @JsonIgnore
    public void beforeToProto() {
        subnet = nwPrefix != null ?
                 IPSubnet.fromString(nwPrefix + "/" + prefixLength) : null;
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void create(UUID bgpId) {
        create();
        this.bgpId = bgpId;
    }

    @JsonIgnore
    public void update(AdRoute from) {
        id = from.id;
    }

}
