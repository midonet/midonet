/*
 * @(#)AdRoute        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

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
}
