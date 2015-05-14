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
import org.midonet.cluster.rest_api.validation.VerifyEnumValue;
import org.midonet.midolman.state.l4lb.VipSessionPersistence;
import org.midonet.packets.IPv4;

import static org.midonet.cluster.util.UUIDUtil.Converter;

@XmlRootElement
public class VIP extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "load_balancer_id", converter = Converter.class)
    public UUID loadBalancerId;

    @NotNull
    @ZoomField(name = "poolId", converter = Converter.class)
    public UUID poolId;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "address")
    public String address;

    @Min(0)
    @Max(65536)
    @ZoomField(name = "protocol_port")
    public int protocolPort;

    @VerifyEnumValue(VipSessionPersistence.class)
    @ZoomField(name = "sessionPersistence")
    public String sessionPersistence;

    @ZoomField(name = "adminStateUp")
    public boolean adminStateUp = true;

    public URI getLoadBalancer() {
        return absoluteUri(ResourceUris.LOAD_BALANCERS, loadBalancerId);
    }

    public URI getPool() {
        return absoluteUri(ResourceUris.POOLS, poolId);
    }

    public URI getUri() {
        return absoluteUri(ResourceUris.VIPS, id);
    }

}
