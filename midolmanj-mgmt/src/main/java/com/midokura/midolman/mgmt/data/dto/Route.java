/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.util.Net;

/**
 * Class representing route.
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
     * Constructor
     */
    public Route() {
    }

    /**
     * Constructor
     *
     * @param id
     *            Route ID
     * @param rt
     *            com.midokura.midolman.layer3.Route object
     */
    public Route(UUID id, com.midokura.midolman.layer3.Route rt) {
        this.id = id;
        this.dstNetworkAddr = Net.convertIntAddressToString(rt.dstNetworkAddr);
        this.dstNetworkLength = rt.dstNetworkLength;
        if (rt.nextHopGateway != com.midokura.midolman.layer3.Route.NO_GATEWAY) {
            this.nextHopGateway = Net
                    .convertIntAddressToString(rt.nextHopGateway);
        }
        this.nextHopPort = rt.nextHopPort;
        this.srcNetworkAddr = Net.convertIntAddressToString(rt.srcNetworkAddr);
        this.srcNetworkLength = rt.srcNetworkLength;
        this.weight = rt.weight;
        this.routerId = rt.routerId;
        this.attributes = rt.attributes;
        if (rt.nextHop == NextHop.BLACKHOLE) {
            this.type = Route.BlackHole;
        } else if (rt.nextHop == NextHop.REJECT) {
            this.type = Route.Reject;
        } else {
            this.type = Route.Normal;
        }
    }

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
        return ResourceUriBuilder.getRoute(getBaseUri(), id);
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + " routerId=" + routerId + ", type=" + type
                + ", srcNetworkAddr=" + srcNetworkAddr + ", srcNetworkLength="
                + srcNetworkLength + ", dstNetworkAddr=" + dstNetworkAddr
                + ", dstNetworkLength=" + dstNetworkLength + ", nextHopPort="
                + nextHopPort + ", nextHopGateway=" + nextHopGateway
                + ", weight=" + weight + ", attributes=" + attributes;
    }
}
