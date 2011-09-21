/*
 * @(#)AdRoute        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;

/**
 * Class representing advertising route.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@XmlRootElement
public class AdRoute {

    private UUID id = null;
    private String nwPrefix = null;
    private byte prefixLength;
    private UUID bgpId = null;

    /**
     * Get AdRoute ID.
     * 
     * @return AdRoute ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set AdRoute ID.
     * 
     * @param id
     *            ID of the AdRoute.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get advertising route nework address.
     * 
     * @return Advertising nework address.
     */
    public String getNwPrefix() {
        return nwPrefix;
    }

    /**
     * Set advertising route nework address.
     * 
     * @param newPrefix
     *            Advertising nework address.
     */
    public void setNwPrefix(String nwPrefix) {
        this.nwPrefix = nwPrefix;
    }

    /**
     * Get advertising route prefix length.
     * 
     * @return Advertising route prefix length.
     */
    public byte getPrefixLength() {
        return prefixLength;
    }

    /**
     * Set advertising route prefix length.
     * 
     * @param prefixLength
     *            Advertising route prefix length.
     */
    public void setPrefixLength(byte prefixLength) {
        this.prefixLength = prefixLength;
    }

    /**
     * Get bgp ID.
     * 
     * @return Bgp ID.
     */
    public UUID getBgpId() {
        return bgpId;
    }

    /**
     * Set bgp ID.
     * 
     * @param bgpId
     *            BGP ID of the advertsing route.
     */
    public void setBgpId(UUID bgpId) {
        this.bgpId = bgpId;
    }

    public AdRouteConfig toConfig() throws UnknownHostException {
        return new AdRouteConfig(this.getBgpId(), InetAddress.getByName(this
                .getNwPrefix()), this.getPrefixLength());
    }
    
    public static AdRoute createAdRoute(UUID id, AdRouteConfig config) {
        AdRoute adRoute = new AdRoute();
        adRoute.setNwPrefix(config.nwPrefix.getHostAddress());
        adRoute.setPrefixLength(config.prefixLength);
        adRoute.setBgpId(config.bgpId);
        adRoute.setId(id);
        return adRoute;
    }
}
