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
import java.util.Arrays;
import java.util.UUID;

import com.google.common.base.Objects;
import com.google.protobuf.Message;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.Host.Interface.class)
public class Interface extends UriResource {

    @ZoomEnum(clazz = Topology.Host.Interface.Type.class)
    public enum InterfaceType {
        @ZoomEnumValue(value = "PHYSICAL")
        Physical,
        @ZoomEnumValue(value = "VIRTUAL")
        Virtual,
        @ZoomEnumValue(value = "TUNNEL")
        Tunnel,
        @ZoomEnumValue(value = "UNKNOWN")
        Unknown
    }

    @ZoomEnum(clazz = Topology.Host.Interface.Endpoint.class)
    public enum Endpoint {
        @ZoomEnumValue(value = "DATAPATH_EP")
        DATAPATH,
        @ZoomEnumValue(value = "PHYSICAL_EP")
        PHYSICAL,
        @ZoomEnumValue(value = "VM_EP")
        VM,
        @ZoomEnumValue(value = "GRE_EP")
        GRE,
        @ZoomEnumValue(value = "CAPWAP_EP")
        CAPWAP,
        @ZoomEnumValue(value = "LOCALHOST_EP")
        LOCALHOST,
        @ZoomEnumValue(value = "TUNTAP_EP")
        TUNTAP,
        @ZoomEnumValue(value = "UNKNOWN_EP")
        UNKNOWN
    }

    @ZoomEnum(clazz = Topology.Host.Interface.DpPortType.class)
    public enum DpPortType {
        @ZoomEnumValue(value = "NET_DEV_DP")
        NetDev,
        @ZoomEnumValue(value = "INTERNAL_DP")
        Internal,
        @ZoomEnumValue(value = "GRE_DP")
        Gre,
        @ZoomEnumValue(value = "VXLAN_DP")
        VXLan,
        @ZoomEnumValue(value = "GRE64_DP")
        Gre64,
        @ZoomEnumValue(value = "LISP_DP")
        Lisp
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;
    @ZoomField(name = "host_id", converter = UUIDUtil.Converter.class)
    private UUID hostId;
    @ZoomField(name = "name")
    private String name;
    @ZoomField(name = "mac")
    private String mac;
    @ZoomField(name = "mtu")
    private int mtu;
    @JsonIgnore
    @ZoomField(name = "up")
    private boolean up;
    @JsonIgnore
    @ZoomField(name = "has_link")
    private boolean hasLink;
    @ZoomField(name = "type")
    private InterfaceType type;
    @ZoomField(name = "endpoint")
    private Endpoint endpoint;
    @ZoomField(name = "port_type")
    private DpPortType portType;
    @ZoomField(name = "addresses", converter = IPAddressUtil.Converter.class)
    private InetAddress[] addresses;

    private int status;

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

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public int getMtu() {
        return mtu;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public boolean isUp() {
        return up;
    }

    public void setUp(boolean up) {
        this.up = up;
    }

    public boolean isHasLink() {
        return hasLink;
    }

    public void setHasLink(boolean hasLink) {
        this.hasLink = hasLink;
    }

    public InterfaceType getType() {
        return type;
    }

    public void setType(
        InterfaceType type) {
        this.type = type;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(
        Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public DpPortType getPortType() {
        return portType;
    }

    public void setPortType(
        DpPortType portType) {
        this.portType = portType;
    }

    public InetAddress[] getAddresses() {
        return addresses;
    }

    public void setAddresses(InetAddress[] addresses) {
        this.addresses = addresses;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.HOSTS, hostId,
                           ResourceUris.INTERFACES, name);
    }

    @JsonIgnore
    @Override
    public void afterFromProto(Message proto) {
        super.afterFromProto(proto);
        status = (up ? 1 : 0) | (hasLink ? 2 : 0);
    }

    @JsonIgnore
    public void beforeToProto() {
        throw new ZoomConvert.ConvertException("Cannot create host interface");
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Interface)) return false;
        final Interface other = (Interface) obj;

        return super.equals(other)
               && mtu == other.mtu
               && up == other.up
               && hasLink == other.hasLink
               && status == other.status
               && endpoint == other.endpoint
               && portType == other.portType
               && Objects.equal(name, other.name)
               && Objects.equal(hostId, other.hostId)
               && Objects.equal(mac, other.mac)
               && Objects.equal(type, other.type)
               && Arrays.deepEquals(addresses, other.addresses);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(),
                                mtu, name, up, hasLink, status, endpoint,
                                portType, hostId, mac, type,
                                Arrays.deepHashCode(addresses));
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("URI", super.toString())
            .add("mtu", mtu)
            .add("name", name)
            .add("up", up)
            .add("hasLink", hasLink)
            .add("status", status)
            .add("endpoint", endpoint)
            .add("portType", portType)
            .add("hostId", hostId)
            .add("mac", mac)
            .add("type", type)
            .add("addresses", Arrays.toString(addresses))
            .toString();
    }

    public static class InterfaceData extends Interface {

        private URI uri;

        @Override
        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        @Override
        public boolean equals(Object obj) {

            if (obj == this) return true;

            if (!(obj instanceof InterfaceData)) return false;
            final InterfaceData other = (InterfaceData) obj;

            return super.equals(other)
                   && Objects.equal(uri, other.uri);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), uri);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("Interface", super.toString())
                .add("uri", uri)
                .toString();
        }
    }
}
