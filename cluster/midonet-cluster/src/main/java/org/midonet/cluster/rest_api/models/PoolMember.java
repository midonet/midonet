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
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.rest_api.validation.VerifyEnumValue;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.packets.IPv4;

import static org.midonet.cluster.rest_api.validation.MessageProperty.IP_ADDR_INVALID;
import static org.midonet.cluster.util.UUIDUtil.Converter;

@XmlRootElement
@Resource(name = ResourceUris.POOL_MEMBERS)
public class PoolMember extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    @ResourceId
    public UUID id;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp = true;

    @ZoomField(name = "status")
    @VerifyEnumValue(LBStatus.class)
    public String status = LBStatus.ACTIVE.toString();

    @NotNull
    @ZoomField(name = "poolId", converter = Converter.class)
    public UUID poolId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = IP_ADDR_INVALID)
    @ZoomField(name = "address")
    public String address;

    @Min(0) @Max(65535)
    @ZoomField(name = "protocolPort")
    public int protocolPort;

    @Min(1)
    @ZoomField(name = "weight")
    public int weight = 1;

    public URI getPool() {
        return absoluteUri(ResourceUris.POOLS, poolId);
    }
}
