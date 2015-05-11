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

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.Ownership;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.util.version.Since;
import org.midonet.util.version.Until;

@XmlRootElement
@Resource(name = ResourceUris.HOSTS)
@ZoomClass(clazz = Topology.Host.class)
public class Host extends UriResource {

    @ResourceId
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @Nonnull
    @ZoomField(name = "name")
    public String name;

    public List<String> addresses;

    @XmlTransient
    @ZoomField(name = "interfaces")
    @Subresource(name = ResourceUris.INTERFACES)
    public List<Interface> interfaces;

    @Ownership
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

    @Subresource(name = ResourceUris.PORTS)
    @ZoomField(name = "port_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> portIds;

    @Override
    public void afterFromProto(Message proto) {
        addresses = new ArrayList<>();
        for (Interface iface : interfaces) {
            for (InetAddress address : iface.addresses) {
                addresses.add(address.toString());
            }
        }
    }

    @Since("3")
    public List<Interface> getHostInterfaces() {
        return interfaces;
    }

    @Until("3")
    public URI getInterfaces() { return getUriFor(ResourceUris.INTERFACES); }

    public URI getPorts() { return getUriFor(ResourceUris.PORTS); }
}
