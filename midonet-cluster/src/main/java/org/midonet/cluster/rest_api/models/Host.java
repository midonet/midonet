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

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.util.version.Until;

@ZoomClass(clazz = Topology.Host.class)
public class Host extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    public List<String> addresses;

    public List<Interface> hostInterfaces;

    public boolean alive;

    /*
     * From specs: This weight is a non-negative integer whose default
     * value is 1. The MN administrator may set this value to zero to signify
     * that the host should never be chosen as a flooding proxy.
     *
     * Note: though null is not a valid value, we accept it to support clients
     * not providing any value (this will be converted to the proper default
     * value when stored and retrieved afterwards).
     */
    @Min(0)
    @Max(65535)
    @ZoomField(name = "flooding_proxy_weight")
    public Integer floodingProxyWeight;

    @ZoomField(name = "port_ids", converter = UUIDUtil.Converter.class)
    @JsonIgnore
    public List<UUID> portIds;

    @ZoomField(name = "tunnel_zone_ids", converter = UUIDUtil.Converter.class)
    @JsonIgnore
    public List<UUID> tunnelZoneIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.HOSTS, id);
    }

    @Until("3")
    public URI getInterfaces() { return relativeUri(ResourceUris.INTERFACES); }

    public URI getPorts() { return relativeUri(ResourceUris.PORTS); }
}
