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
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.BgpPeer.class)
public class BgpPeer extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "as_number")
    @Min(1)
    public int asNumber;

    @ZoomField(name = "address", converter = IPAddressUtil.Converter.class)
    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String address;

    @ZoomField(name = "keep_alive")
    @Min(5)
    @Max(3600)
    public Integer keepAlive;

    @ZoomField(name = "hold_time")
    @Min(5)
    @Max(7200)
    public Integer holdTime;

    @ZoomField(name = "connect_retry")
    @Min(5)
    @Max(3600)
    public Integer connectRetry;

    @ZoomField(name = "password")
    public String password;

    @JsonIgnore
    @ZoomField(name = "router_id")
    public UUID routerId;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BGP_PEERS(), id);
    }

    public URI getRouter() {
        return absoluteUri(ResourceUris.ROUTERS(), routerId);
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void create(UUID routerId) {
        create();
        this.routerId = routerId;
    }

    @JsonIgnore
    public void update(BgpPeer from) {
        id = from.id;
        routerId = from.routerId;
    }

}
