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
package org.midonet.api.bgp;

import org.midonet.api.UriResource;
import org.midonet.api.ResourceUriBuilder;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.UUID;

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
     * @param data
     *            AdRoute data object
     */
    public AdRoute(org.midonet.cluster.data.AdRoute data) {
        this(data.getId(), data.getNwPrefix().getHostAddress(),
                data.getPrefixLength(), data.getBgpId());
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
     * @param nwPrefix
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

    public org.midonet.cluster.data.AdRoute toData () {

        try {
            return new org.midonet.cluster.data.AdRoute()
                    .setId(this.id)
                    .setBgpId(this.bgpId)
                    .setNwPrefix(InetAddress.getByName(this.nwPrefix))
                    .setPrefixLength(this.prefixLength);
        } catch (UnknownHostException e) {
            throw new RuntimeException("Invalid nwPrefix: " + this.nwPrefix, e);
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
