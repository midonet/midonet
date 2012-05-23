/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;

/**
 * Class representing advertising route.
 */
@XmlRootElement
public class AdRoute extends UriResource {

    private UUID id = null;
    private String nwPrefix = null;
    private byte prefixLength;
    private UUID bgpId = null;

    /**
     * Constructor
     */
    public AdRoute() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of ad route
     * @param config
     *            AdRouteConfig object
     */
    public AdRoute(UUID id, AdRouteConfig config) {
        this(id, config.nwPrefix.getHostAddress(), config.prefixLength,
                config.bgpId);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of ad route
     * @param nwPrefix
     *            Network IP prefix
     * @param prefixLength
     *            Network IP prefix length
     * @param bgpId
     *            BGP ID
     */
    public AdRoute(UUID id, String nwPrefix, byte prefixLength, UUID bgpId) {
        this.id = id;
        this.nwPrefix = nwPrefix;
        this.prefixLength = prefixLength;
        this.bgpId = bgpId;
    }

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
     * @return the BGP URI
     */
    public URI getBgp() {
        if (getBaseUri() != null && bgpId != null) {
            return ResourceUriBuilder.getBgp(getBaseUri(), bgpId);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getAdRoute(getBaseUri(), id);
        } else {
            return null;
        }
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

    public AdRouteConfig toConfig() {
        try {
            return new AdRouteConfig(this.getBgpId(),
                    InetAddress.getByName(this.getNwPrefix()),
                    this.getPrefixLength());
        } catch (UnknownHostException ex) {
            // This exception should never be thrown: Return null.
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", nwPrefix=" + nwPrefix + ", prefixLength="
                + prefixLength + ", bgp=" + bgpId;
    }
}
