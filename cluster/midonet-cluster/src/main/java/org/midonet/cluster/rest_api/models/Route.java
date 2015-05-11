package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.google.protobuf.Message;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.Route.class)
public class Route extends UriResource {

    @ZoomEnum(clazz = Topology.Route.NextHop.class)
    enum NextHop {
        @ZoomEnumValue(value = "PORT")Normal,
        @ZoomEnumValue(value = "BLACKHOLE")BlackHole,
        @ZoomEnumValue(value = "REJECT")Reject
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @ZoomField(name = "next_hop_port_id", converter = UUIDUtil.Converter.class)
    public UUID nextHopPort;

    @ZoomField(name = "attributes")
    public String attributes;

    @ZoomField(name = "dst_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> dstSubnet;

    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String dstNetworkAddr;

    @Min(0)
    @Max(32)
    public int dstNetworkLength;

    @ZoomField(name = "src_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> srcSubnet;

    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String srcNetworkAddr;

    @Min(0)
    @Max(32)
    public int srcNetworkLength;

    @ZoomField(name = "next_hop_gateway",
        converter = IPAddressUtil.Converter.class)
    @Pattern(regexp = IPv4.regex)
    public String nextHopGateway;

    public boolean learned;

    @NotNull
    public NextHop type;

    @Min(0)
    public int weight;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.ROUTES, id);
    }

    public URI getRouter() {
        return absoluteUri(ResourceUris.ROUTERS, routerId);
    }

    @Override
    public void afterFromProto(Message message) {
        if (null != dstSubnet) {
            dstNetworkAddr = dstSubnet.getAddress().toString();
            dstNetworkLength = dstSubnet.getPrefixLen();
        }
        if (null != srcSubnet) {
            srcNetworkAddr = srcSubnet.getAddress().toString();
            srcNetworkLength = srcSubnet.getPrefixLen();
        }
    }

    @Override
    public void beforeToProto() {
        if (StringUtils.isNotEmpty(dstNetworkAddr)) {
            dstSubnet =
                IPSubnet.fromString(dstNetworkAddr + "/" + dstNetworkLength);
        }
        if (StringUtils.isNotEmpty(srcNetworkAddr)) {
            srcSubnet =
                IPSubnet.fromString(srcNetworkAddr + "/" + srcNetworkLength);
        }
    }
}