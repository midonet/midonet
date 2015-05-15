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

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import com.google.common.base.Objects;
import com.google.protobuf.Message;

import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.util.collection.ListUtil;
import org.midonet.util.version.Since;
import org.midonet.util.version.Until;

@ZoomClass(clazz = Topology.Host.class)
public class Host extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;

    @ZoomField(name = "name")
    private String name;

    private List<String> addresses;

    @ZoomField(name = "interfaces")
    @Since("3")
    private List<Interface> hostInterfaces;

    private boolean alive;

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
    private Integer floodingProxyWeight;

    @ZoomField(name = "port_ids", converter = UUIDUtil.Converter.class)
    @JsonIgnore
    private List<UUID> portIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.HOSTS, id);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<String> addresses) {
        this.addresses = addresses;
    }

    public List<Interface> getHostInterfaces() {
        return hostInterfaces;
    }

    public void setHostInterfaces(
        List<Interface> hostInterfaces) {
        this.hostInterfaces = hostInterfaces;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public Integer getFloodingProxyWeight() {
        return floodingProxyWeight;
    }

    public void setFloodingProxyWeight(Integer floodingProxyWeight) {
        this.floodingProxyWeight = floodingProxyWeight;
    }

    public List<UUID> getPortIds() {
        return portIds;
    }

    public void setPortIds(List<UUID> portIds) {
        this.portIds = portIds;
    }

    @Until("3")
    public URI getInterfaces() {
        return relativeUri(ResourceUris.INTERFACES);
    }

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }

    @JsonIgnore
    @Override
    public void afterFromProto(Message proto) {
        addresses = new ArrayList<>();
        for (Interface iface : hostInterfaces) {
            for (InetAddress address : iface.getAddresses()) {
                addresses.add(address.toString());
            }
        }
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this)
            return true;

        if (!(obj instanceof Host))
            return false;
        final Host other = (Host) obj;

        return alive == other.alive
               && Objects.equal(id, other.id)
               && Objects.equal(name, other.name)
               && Objects.equal(floodingProxyWeight,
                                other.floodingProxyWeight)
               && ListUtils.isEqualList(portIds, other.portIds)
               && ListUtils.isEqualList(addresses, other.addresses)
               && ListUtils.isEqualList(hostInterfaces, other.hostInterfaces)
               && ListUtils.isEqualList(portIds, other.portIds);
    }

    @Override
    public int hashCode() {
        return Objects
            .hashCode(super.hashCode(), id, name, alive, floodingProxyWeight,
                      ListUtils.hashCodeForList(addresses),
                      ListUtils.hashCodeForList(portIds),
                      ListUtils.hashCodeForList(hostInterfaces));
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("URI", super.toString())
            .add("id", id)
            .add("name", name)
            .add("alive", alive)
            .add("floodingProxyWeight", floodingProxyWeight)
            .add("addresses", ListUtil.toString(portIds))
            .add("hostInterfaces", ListUtil.toString(portIds))
            .add("portIds", ListUtil.toString(portIds))
            .toString();
    }

    public static class HostData extends Host {

        @Since("3")
        private List<Interface.InterfaceData> hostInterfaces;

        private URI interfaces;
        private URI ports;
        private URI uri;

        @Override
        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        @Override
        public URI getInterfaces() {
            return interfaces;
        }

        public void setInterfaces(URI interfaces) {
            this.interfaces = interfaces;
        }

        @Override
        public URI getPorts() {
            return ports;
        }

        public void setPorts(URI ports) {
            this.ports = ports;
        }

        @JsonIgnore
        @Override
        public List<Interface> getHostInterfaces() {
            return null;
        }

        @JsonIgnore
        @Override
        public void setHostInterfaces(List<Interface> hostInterfaces) {
        }

        @JsonProperty("host_interfaces")
        public List<Interface.InterfaceData> getHostInterfacesData() {
            return hostInterfaces;
        }

        @JsonProperty("host_interfaces")
        public void setHostInterfacesData(
            List<Interface.InterfaceData> hostInterfaces) {
            this.hostInterfaces = hostInterfaces;
        }

        @Override
        public boolean equals(Object obj) {

            if (obj == this) return true;

            if (!(obj instanceof HostData)) return false;
            final HostData other = (HostData) obj;

            return Objects.equal(interfaces, other.interfaces)
                   && Objects.equal(ports, other.ports)
                   && Objects.equal(uri, other.uri)
                   && ListUtils.isEqualList(hostInterfaces,
                                            other.hostInterfaces);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), interfaces, ports, uri,
                                    ListUtils.hashCodeForList(hostInterfaces));
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("Host", super.toString())
                .add("interfaces", interfaces)
                .add("ports", ports)
                .add("uri", uri)
                .add("hostInterfaces", ListUtil.toString(hostInterfaces))
                .toString();
        }
    }
}