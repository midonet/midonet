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
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_AD_ROUTE_COLLECTION_JSON;

public class Bgp extends ResourceBase<Bgp, DtoBgp> {

    public Bgp(WebResource resource, URI uriForCreation, DtoBgp bgp) {
        super(resource, uriForCreation, bgp,
              MidonetMediaTypes.APPLICATION_BGP_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public int getPeerAS() {
        return principalDto.getPeerAS();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public int getLocalAS() {
        return principalDto.getLocalAS();
    }

    public String getPeerAddr() {
        return principalDto.getPeerAddr();
    }

    public Bgp localAS(int localAS) {
        principalDto.setLocalAS(localAS);
        return this;
    }

    public Bgp peerAddr(String peerAddr) {
        principalDto.setPeerAddr(peerAddr);
        return this;
    }

    public Bgp peerAS(int peerAS) {
        principalDto.setPeerAS(peerAS);
        return this;
    }

    public ResourceCollection<AdRoute> getAdRoutes(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(principalDto.getAdRoutes(),
                                 queryParams,
                                 APPLICATION_AD_ROUTE_COLLECTION_JSON(),
                                 AdRoute.class, DtoAdRoute.class);
    }

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
