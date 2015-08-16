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

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.state.VtepConnectionState;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.Vtep.class)
public class Vtep extends UriResource {

    @JsonIgnore
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "management_ip", converter = IPAddressUtil.Converter.class)
    public String managementIp;

    @Min(1)
    @Max(65535)
    @ZoomField(name = "management_port")
    public int managementPort;

    @ZoomField(name = "tunnel_zone_id", converter = UUIDUtil.Converter.class)
    public UUID tunnelZoneId;

    public String name;

    public String description;

    public VtepConnectionState connectionState;

    public List<String> tunnelIpAddrs;

    @JsonIgnore
    @ZoomField(name = "bindings")
    public List<Topology.Vtep.Binding> bindings;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.VTEPS, managementIp);
    }

    public URI getBindings() {
        return relativeUri(ResourceUris.BINDINGS);
    }

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }

    public String getVtepBindingTemplate() {
        return getUri() + "/{portName}/{vlanId}";
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(Vtep from) {
        id = from.id;
        bindings = from.bindings;
    }

}
