/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.layer3;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

@JsonPropertyOrder(alphabetic=true)
public class Route extends ZoomObject implements Serializable {

    private static final long serialVersionUID = -5913569441176193396L;
    public static final int NO_GATEWAY = 0xffffffff;

    @ZoomEnum(clazz = Topology.Route.NextHop.class)
    public enum NextHop {
        @ZoomEnumValue(value = "BLACKHOLE")
        BLACKHOLE,
        @ZoomEnumValue(value = "REJECT")
        REJECT,
        @ZoomEnumValue(value = "PORT")
        PORT,
        @ZoomEnumValue(value = "LOCAL")
        LOCAL;

        public boolean toPort() {
            return this.equals(PORT) || this.equals(LOCAL);
        }

        public Topology.Route.NextHop toProto() {
            return Topology.Route.NextHop.valueOf(toString());
        }
    }

    @ZoomField(name = "src_subnet", converter = SubnetConverter.class)
    public int srcNetworkAddr;
    @ZoomField(name = "src_subnet", converter = PrefixConverter.class)
    public int srcNetworkLength;
    @ZoomField(name = "dst_subnet", converter = SubnetConverter.class)
    public int dstNetworkAddr;
    @ZoomField(name = "dst_subnet", converter = PrefixConverter.class)
    public int dstNetworkLength;
    @ZoomField(name = "next_hop")
    public NextHop nextHop;
    @ZoomField(name = "next_hop_port_id")
    public UUID nextHopPort;
    @ZoomField(name = "next_hop_gateway", converter = AddressConverter.class)
    public int nextHopGateway;
    @ZoomField(name = "weight")
    public int weight;
    @ZoomField(name = "attributes")
    public String attributes;
    @ZoomField(name = "router_id")
    public UUID routerId;
    public boolean learned = false;

    public Route(int srcNetworkAddr, int srcNetworkLength, int dstNetworkAddr,
            int dstNetworkLength, NextHop nextHop, UUID nextHopPort,
            int nextHopGateway, int weight, String attributes, UUID routerId,
            boolean learned) {
        this.srcNetworkAddr = srcNetworkAddr;
        this.srcNetworkLength = srcNetworkLength;
        this.dstNetworkAddr = dstNetworkAddr;
        this.dstNetworkLength = dstNetworkLength;
        this.nextHop = nextHop;
        this.nextHopPort = nextHopPort;
        this.nextHopGateway = nextHopGateway;
        this.weight = weight;
        this.attributes = attributes;
        this.routerId = routerId;
        this.learned = learned;
    }

    public Route(int srcNetworkAddr, int srcNetworkLength, int dstNetworkAddr,
                 int dstNetworkLength, NextHop nextHop, UUID nextHopPort,
                 int nextHopGateway, int weight, String attributes,
                 UUID routerId) {
        this(srcNetworkAddr, srcNetworkLength, dstNetworkAddr, dstNetworkLength,
             nextHop, nextHopPort, nextHopGateway, weight, attributes, routerId,
             false /* learned */);
    }

    public Route(IPv4Subnet srcSubnet, IPv4Subnet dstSubnet,
                 NextHop nextHop, UUID nextHopPortId, IPv4Addr nextHopGw,
                 int weight, UUID routerId) {
        this(srcSubnet.getIntAddress(), srcSubnet.getPrefixLen(),
                dstSubnet.getIntAddress(), dstSubnet.getPrefixLen(),
                nextHop, nextHopPortId,
                nextHopGw == null ? 0 : nextHopGw.addr(),
                weight, null, routerId);
    }

    // Default constructor for the Jackson deserialization.
    public Route() { }

    /* Custom accessors for more readable IP address representation in Jackson
    serialization. */

    public boolean isLearned() {
        return this.learned;
    }

    public void setLearned(boolean learned) {
        this.learned = learned;
    }


    public String getSrcNetworkAddr() {
        return IPv4Addr.intToString(this.srcNetworkAddr);
    }

    public void setSrcNetworkAddr(String addr) {
        this.srcNetworkAddr = IPv4Addr.stringToInt(addr);
    }

    public String getDstNetworkAddr() {
        return IPv4Addr.intToString(this.dstNetworkAddr);
    }

    public void setDstNetworkAddr(String addr) {
        this.dstNetworkAddr = IPv4Addr.stringToInt(addr);
    }

    public String getNextHopGateway() {
        return IPv4Addr.intToString(this.nextHopGateway);
    }

    public void setNextHopGateway(String addr) {
        this.nextHopGateway = IPv4Addr.stringToInt(addr);
    }

    @Override
    public final boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null) return false;
        if (!(obj instanceof Route)) return false;
        final Route rt = (Route) obj;
        return srcNetworkAddr == rt.srcNetworkAddr &&
               srcNetworkLength == rt.srcNetworkLength &&
               dstNetworkAddr == rt.dstNetworkAddr &&
               dstNetworkLength == rt.dstNetworkLength &&
               Objects.equal(nextHop, rt.nextHop) &&
               Objects.equal(nextHopPort, rt.nextHopPort) &&
               nextHopGateway == rt.nextHopGateway &&
               Objects.equal(routerId, rt.routerId) &&
               learned == rt.learned;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(srcNetworkAddr, srcNetworkLength,
                                dstNetworkAddr, dstNetworkLength, nextHop,
                                nextHopPort, nextHopGateway,
                                routerId, learned);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(IPv4Addr.intToString(srcNetworkAddr)).append("/");
        sb.append(srcNetworkLength).append(",");
        sb.append(IPv4Addr.intToString(dstNetworkAddr)).append("/");
        sb.append(dstNetworkLength).append(",");
        if (learned)
            sb.append("learned,");
        if (null != nextHop)
            sb.append(nextHop.toString());
        sb.append(",");
        if (null != nextHopPort)
            sb.append(nextHopPort.toString());
        sb.append(",");
        sb.append(nextHopGateway).append(",");
        sb.append(weight).append(",");
        if (null != attributes)
            sb.append(attributes);
        if (null != routerId)
            sb.append(routerId);
        return sb.toString();
    }

    public static Route fromString(String str) {
        String[] parts = str.split(",");
        return new Route(Integer.parseInt(parts[0]),
                Byte.parseByte(parts[1]), Integer.parseInt(parts[2]),
                Byte.parseByte(parts[3]), parts[4].isEmpty() ? null
                        : NextHop.valueOf(parts[4]), parts[5].isEmpty() ? null
                        : UUID.fromString(parts[5]),
                Integer.parseInt(parts[6]), Integer.parseInt(parts[7]),
                parts.length > 8? parts[8] : null, null);
    }

    public static Route defaultRoute(UUID nextHopPortId, int weight,
                                     UUID routerId) {
        return new Route(0, 0, 0, 0, NextHop.PORT, nextHopPortId,
                Route.NO_GATEWAY, weight, null, routerId);
    }

    public static Route nextHopPortRoute(IPv4Subnet srcSubnet,
                                         IPv4Subnet dstSubnet,
                                         UUID nextHopPortId, IPv4Addr nextHopGw,
                                         int weight, UUID routerId) {
        return new Route(srcSubnet, dstSubnet, NextHop.PORT,
                nextHopPortId, nextHopGw, weight, routerId);
    }

    public static Route localRoute(UUID portId, int portAddr, UUID routerId) {
        return new Route(0, 0, portAddr, 32, Route.NextHop.LOCAL,
                portId, Route.NO_GATEWAY, 0, null, routerId);
    }

    @JsonIgnore
    public boolean hasDstSubnet(IPv4Subnet sub) {
        if (sub == null) {
            throw new IllegalArgumentException("sub must not be NULL");
        }
        return dstNetworkAddr == sub.getIntAddress() &&
                dstNetworkLength == sub.getPrefixLen();
    }

    @JsonIgnore
    public boolean hasNextHopGateway(int gateway) {
        return nextHopGateway == gateway;
    }

    public static class AddressConverter
        extends ZoomConvert.Converter<Integer, Commons.IPAddress> {
        @Override
        public Commons.IPAddress toProto(Integer value, Type clazz) {
            return Commons.IPAddress.newBuilder()
                .setAddress(IPv4Addr.apply(value).toString())
                .setVersion(Commons.IPVersion.V4)
                .build();
        }
        @Override
        public Integer fromProto(Commons.IPAddress value, Type clazz) {
            return IPv4Addr.apply(value.getAddress()).toInt();
        }
    }

    public static class SubnetConverter
        extends ZoomConvert.Converter<Integer, Commons.IPSubnet> {
        @Override
        public Commons.IPSubnet toProto(Integer value, Type clazz) {
            throw new ZoomConvert.ConvertException("Not supported");
        }
        @Override
        public Integer fromProto(Commons.IPSubnet value, Type clazz) {
            return IPv4Addr.apply(value.getAddress()).toInt();
        }
    }

    public static class PrefixConverter
        extends ZoomConvert.Converter<Integer, Commons.IPSubnet> {
        @Override
        public Commons.IPSubnet toProto(Integer value, Type clazz) {
            throw new ZoomConvert.ConvertException("Not supported");
        }
        @Override
        public Integer fromProto(Commons.IPSubnet value, Type clazz) {
            return value.getPrefixLength();
        }
    }
}
