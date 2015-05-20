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

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoAdRoute;
import org.midonet.client.dto.DtoBgp;
import org.midonet.cluster.rest_api.VendorMediaType;

public class Bgp extends ResourceBase<Bgp, DtoBgp> {

    public Bgp(WebResource resource, URI uriForCreation, DtoBgp bgp) {
        super(resource, uriForCreation, bgp,
              VendorMediaType.APPLICATION_BGP_JSON);
    }

    /**
     * Gets URI of this resource
     *
     * @return URI of this reosurce
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets peer AS number
     *
     * @return peer AS number
     */
    public int getPeerAS() {
        return principalDto.getPeerAS();
    }

    /**
     * Gets ID of this resource
     *
     * @return UUID of this resource
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets local AS number
     *
     * @return local AS number
     */
    public int getLocalAS() {
        return principalDto.getLocalAS();
    }

    /**
     * Gets ip address of the peer
     *
     * @return ip address of the peer
     */

    public String getPeerAddr() {
        return principalDto.getPeerAddr();
    }

    /**
     * Sets local AS number
     *
     * @param localAS local AS number for the BGP
     * @return this
     */
    public Bgp localAS(int localAS) {
        principalDto.setLocalAS(localAS);
        return this;
    }

    /**
     * Sets peer BGP address
     *
     * @param peerAddr IP address of the peer
     * @return this
     */
    public Bgp peerAddr(String peerAddr) {
        principalDto.setPeerAddr(peerAddr);
        return this;
    }

    /**
     * Sets peer BGP AS number
     *
     * @param peerAS peer's AS number
     * @return this
     */
    public Bgp peerAS(int peerAS) {
        principalDto.setPeerAS(peerAS);
        return this;
    }

    /**
     * Returns collections of Advertised Routes to the BGP peers
     *
     * @return collection of AdRoute resource
     */
    public ResourceCollection<AdRoute> getAdRoutes(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getAdRoutes(),
                                 queryParams,
                                 VendorMediaType
                                     .APPLICATION_AD_ROUTE_COLLECTION_JSON,
                                 AdRoute.class, DtoAdRoute.class);
    }

    /**
     * Adds AdRoute resource under this Bgp
     *
     * @return new AdRoute() resource
     */
    public AdRoute addAdRoute() {
        return new AdRoute(resource, principalDto.getAdRoutes(),
                           new DtoAdRoute());
    }

    @Override
    public String toString() {
        return String.format("Bgp{localAs=%s, peerAs=%s, peerAddr=%s",
                             getLocalAS(), getPeerAS(), getPeerAddr());
    }
}
