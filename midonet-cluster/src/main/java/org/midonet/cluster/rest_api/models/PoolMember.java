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
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.packets.IPv4;

import static org.midonet.cluster.rest_api.validation.MessageProperty.IP_ADDR_INVALID;

@ZoomClass(clazz = Topology.PoolMember.class)
public class PoolMember extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "status")
    public LBStatus status;

    @NotNull
    @ZoomField(name = "pool_id")
    public UUID poolId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = IP_ADDR_INVALID)
    @ZoomField(name = "address", converter = IPAddressUtil.Converter.class)
    public String address;

    @Min(0)
    @Max(65535)
    @ZoomField(name = "protocol_port")
    public int protocolPort;

    @Min(1)
    @ZoomField(name = "weight")
    public int weight = 1;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.POOL_MEMBERS, id);
    }

    public URI getPool() {
        return absoluteUri(ResourceUris.POOLS, poolId);
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        adminStateUp = true;
        status = LBStatus.ACTIVE;
    }

    @JsonIgnore
    public void create(UUID poolId) {
        create();
        this.poolId = poolId;
    }

    @JsonIgnore
    public void update(PoolMember from) {
        id = from.id;
        address = from.address;
        protocolPort = from.protocolPort;
        status = from.status;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("adminStateUp", adminStateUp)
            .add("status", status)
            .add("poolId", poolId)
            .add("address", address)
            .add("protocolPort", protocolPort)
            .add("weight", weight)
            .toString();
    }
}
