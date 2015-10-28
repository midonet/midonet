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
package org.midonet.midolman.host.interfaces;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.util.EnumConverter;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.MACUtil;
import org.midonet.odp.DpPort;
import org.midonet.packets.MAC;

import static org.midonet.cluster.models.State.HostState.Interface;

/**
 * Represents a host network interface.
 */
@ZoomClass(clazz = Interface.class)
public class InterfaceDescription extends ZoomObject {

    @ZoomEnum(clazz = Interface.Type.class)
    public enum Type {
        @ZoomEnumValue(value = "PHYSICAL")
        PHYS,
        @ZoomEnumValue(value = "VIRTUAL")
        VIRT,
        @ZoomEnumValue(value = "TUNNEL")
        TUNN,
        @ZoomEnumValue(value = "UNKNOWN")
        UNKNOWN
    }
    @ZoomEnum(clazz = Interface.Endpoint.class)
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

    ///////////////////////////////////////////////////////////////////////////
    // Attributes
    ///////////////////////////////////////////////////////////////////////////
    @ZoomField(name = "name")
    protected String name;
    @ZoomField(name = "type")
    protected Type type;
    @ZoomField(name = "mac", converter = MACUtil.Converter.class)
    protected MAC mac;
    @ZoomField(name = "addresses", converter = IPAddressUtil.Converter.class)
    protected List<InetAddress> inetAddresses;
    @ZoomField(name = "up")
    protected boolean isUp;
    @ZoomField(name = "has_link")
    protected boolean hasLink;
    @ZoomField(name = "mtu")
    protected int mtu;
    @ZoomField(name = "endpoint")
    protected Endpoint endpoint;
    @ZoomField(name = "port_type", converter = DpPortTypeMapConverter.class)
    protected DpPort.Type portType;
    //protected ... other
    protected Map<String, String> properties;

    ///////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////
    public InterfaceDescription() { }

    public InterfaceDescription(String name) {
        this.name = name;
        this.type = Type.UNKNOWN;
        this.mac = MAC.fromString("00:00:00:00:00:00");
        this.inetAddresses = new LinkedList<>();
        this.isUp = false;
        this.hasLink = false;
        this.mtu = 0;
        this.endpoint = Endpoint.UNKNOWN;
        properties = new HashMap<>();
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public void setMac(String macString) {
        this.mac = MAC.fromString(macString);
    }

    public void setMac(MAC mac) {
        this.mac = mac;
    }

    public MAC getMac() {
        return mac;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public void setInetAddress(InetAddress inetAddress) {
        this.inetAddresses.add(inetAddress);
    }

    public void setInetAddress(String addressString) {
        try {
            InetAddress inetAddress = InetAddress.getByName(addressString);
            this.inetAddresses.add(inetAddress);
        } catch (Exception ignored) {
            // We allow the interfaceDescription not to have any IP addresses.
            // If the IP address conversion fails, we just don't add it to
            // the list.
        }
    }

    public List<InetAddress> getInetAddresses() {
        return inetAddresses;
    }

    public void setUp(boolean up) {
        isUp = up;
    }

    public boolean isUp() {
        return isUp;
    }

    public void setHasLink(boolean hasLink) {
        this.hasLink = hasLink;
    }

    public boolean hasLink() {
        return hasLink;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public int getMtu() {
        return mtu;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public DpPort.Type getPortType() {
        return portType;
    }

    public void setPortType(DpPort.Type portType) {
        this.portType = portType;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public String toString() {
        return "InterfaceDescription{" +
            "name='" + name + '\'' +
            ", type=" + type +
            ", mac=" + mac +
            ", inetAddresses=" + inetAddresses +
            ", isUp=" + isUp +
            ", hasLink=" + hasLink +
            ", mtu=" + mtu +
            ", endpoint=" + endpoint +
            ", portType=" + portType +
            ", properties=" + properties +
            '}';
    }

    public String logString() {
        return name + "(" +
                type +
                " mac " + mac +
                " inet " + inetAddresses +
                " mtu=" + mtu +
                " " + ((isUp) ? "UP" : "DOWN") +
                "," + ((hasLink) ? "LINK" : "NOLINK") +
                "," + endpoint +
                "," + portType +
                ')';
    }

    /** A converter for the datapath port type. We cannot use ZOOM annotations
     * because the DpPort.Type enumeration is found in the ODP module, which
     * does not import cluster. */
    public static class DpPortTypeMapConverter
        extends EnumConverter<DpPort.Type, Interface.DpPortType> {

        public DpPortTypeMapConverter() {
            add(DpPort.Type.NetDev, Interface.DpPortType.NET_DEV_DP);
            add(DpPort.Type.Internal, Interface.DpPortType.INTERNAL_DP);
            add(DpPort.Type.Gre, Interface.DpPortType.GRE_DP);
            add(DpPort.Type.VXLan, Interface.DpPortType.VXLAN_DP);
            add(DpPort.Type.Gre64, Interface.DpPortType.GRE64_DP);
            add(DpPort.Type.Lisp, Interface.DpPortType.LISP_DP);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (null == obj || !(obj instanceof InterfaceDescription)) return false;
        InterfaceDescription id = (InterfaceDescription)obj;
        return Objects.equals(name, id.name) &&
               type == id.type &&
               Objects.equals(mac, id.mac) &&
               Objects.equals(inetAddresses, id.inetAddresses) &&
               isUp == id.isUp &&
               hasLink == id.hasLink &&
               mtu == id.mtu &&
               endpoint == id.endpoint &&
               portType == id.portType &&
               Objects.equals(properties, id.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, mac, inetAddresses, isUp, hasLink, mtu,
                            endpoint, portType, properties);
    }
}
