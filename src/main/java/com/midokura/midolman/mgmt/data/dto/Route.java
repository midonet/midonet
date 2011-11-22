/*
 * @(#)Route      1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.util.Net;

/**
 * Class representing route.
 *
 * @version 1.6 10 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Route extends UriResource {

    public static final String Normal = "Normal";
    public static final String BlackHole = "BlackHole";
    public static final String Reject = "Reject";

    private UUID id = null;
    private UUID routerId = null;
    private String srcNetworkAddr = null;
    private int srcNetworkLength;
    private String dstNetworkAddr = null;
    private int dstNetworkLength;
    private UUID nextHopPort = null;
    private String nextHopGateway = null;
    private int weight;
    private String attributes;
    private String type;

    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * @return the routerId
     */
    public UUID getRouterId() {
        return routerId;
    }

    /**
     * @param routerId
     *            the routerId to set
     */
    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * @return the srcNetworkAddr
     */
    public String getSrcNetworkAddr() {
        return srcNetworkAddr;
    }

    /**
     * @param srcNetworkAddr
     *            the srcNetworkAddr to set
     */
    public void setSrcNetworkAddr(String srcNetworkAddr) {
        this.srcNetworkAddr = srcNetworkAddr;
    }

    /**
     * @return the srcNetworkLength
     */
    public int getSrcNetworkLength() {
        return srcNetworkLength;
    }

    /**
     * @param srcNetworkLength
     *            the srcNetworkLength to set
     */
    public void setSrcNetworkLength(int srcNetworkLength) {
        this.srcNetworkLength = srcNetworkLength;
    }

    /**
     * @return the dstNetworkAddr
     */
    public String getDstNetworkAddr() {
        return dstNetworkAddr;
    }

    /**
     * @param dstNetworkAddr
     *            the dstNetworkAddr to set
     */
    public void setDstNetworkAddr(String dstNetworkAddr) {
        this.dstNetworkAddr = dstNetworkAddr;
    }

    /**
     * @return the dstNetworkLength
     */
    public int getDstNetworkLength() {
        return dstNetworkLength;
    }

    /**
     * @param dstNetworkLength
     *            the dstNetworkLength to set
     */
    public void setDstNetworkLength(int dstNetworkLength) {
        this.dstNetworkLength = dstNetworkLength;
    }

    /**
     * @return the nextHopPort
     */
    public UUID getNextHopPort() {
        return nextHopPort;
    }

    /**
     * @param nextHopPort
     *            the nextHopPort to set
     */
    public void setNextHopPort(UUID nextHopPort) {
        this.nextHopPort = nextHopPort;
    }

    /**
     * @return the nextHopGateway
     */
    public String getNextHopGateway() {
        return nextHopGateway;
    }

    /**
     * @param nextHopGateway
     *            the nextHopGateway to set
     */
    public void setNextHopGateway(String nextHopGateway) {
        this.nextHopGateway = nextHopGateway;
    }

    /**
     * @return the weight
     */
    public int getWeight() {
        return weight;
    }

    /**
     * @param weight
     *            the weight to set
     */
    public void setWeight(int weight) {
        this.weight = weight;
    }

    /**
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * @param type
     *            the type to set
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * @return the attributes
     */
    public String getAttributes() {
        return attributes;
    }

    /**
     * @param attributes
     *            the attributes to set
     */
    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getRoute(getBaseUri(), id);
    }

    public com.midokura.midolman.layer3.Route toZkRoute() {
        NextHop nextHop = null;
        String type = this.getType();
        int gateway = com.midokura.midolman.layer3.Route.NO_GATEWAY;
        if (type.equals(Route.Reject)) {
            nextHop = NextHop.REJECT;
        } else if (type.equals(Route.BlackHole)) {
            nextHop = NextHop.BLACKHOLE;
        } else {
            if (this.getNextHopGateway() != null) {
                gateway = Net.convertStringAddressToInt(this
                        .getNextHopGateway());
            }
            nextHop = NextHop.PORT;
        }

        return new com.midokura.midolman.layer3.Route(
                Net.convertStringAddressToInt(this.getSrcNetworkAddr()),
                this.getSrcNetworkLength(), Net.convertStringAddressToInt(this
                        .getDstNetworkAddr()), this.getDstNetworkLength(),
                nextHop, this.getNextHopPort(), gateway, this.getWeight(),
                this.getAttributes(), this.getRouterId());
    }

    public static Route createRoute(UUID id,
            com.midokura.midolman.layer3.Route rt) {
        Route route = new Route();
        route.setDstNetworkAddr(Net
                .convertIntAddressToString(rt.dstNetworkAddr));
        route.setDstNetworkLength(rt.dstNetworkLength);
        route.setNextHopGateway(Net
                .convertIntAddressToString(rt.nextHopGateway));
        route.setNextHopPort(rt.nextHopPort);
        route.setSrcNetworkAddr(Net
                .convertIntAddressToString(rt.srcNetworkAddr));
        route.setSrcNetworkLength(rt.srcNetworkLength);
        route.setWeight(rt.weight);
        route.setRouterId(rt.routerId);
        route.setAttributes(rt.attributes);
        if (rt.nextHop == NextHop.BLACKHOLE) {
            route.setType(Route.BlackHole);
        } else if (rt.nextHop == NextHop.REJECT) {
            route.setType(Route.Reject);
        } else {
            route.setType(Route.Normal);
        }
        route.setId(id);
        return route;
    }
}
