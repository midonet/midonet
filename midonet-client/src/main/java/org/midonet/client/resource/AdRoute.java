/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoAdRoute;


public class AdRoute extends ResourceBase<AdRoute, DtoAdRoute> {

    public AdRoute(WebResource resource, URI uriForCeration,
                   DtoAdRoute adRoute) {
        super(resource, uriForCeration, adRoute,
                VendorMediaType.APPLICATION_AD_ROUTE_JSON);
    }

    /*
     * Delegate getters
     */

    /**
     * Returns URI of this resource
     *
     * @return URI of this resource
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Returns prefix length of advertising route
     *
     * @return prefix length
     */
    public int getPrefixLength() {
        return principalDto.getPrefixLength();
    }

    /**
     * Returns prefix of advertising route
     *
     * @return
     */
    public String getNwPrefix() {
        return principalDto.getNwPrefix();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    /*
     * Parameter setters
     */

    public AdRoute nwPrefix(String nwPrefix) {
        principalDto.setNwPrefix(nwPrefix);
        return this;
    }

    public AdRoute prefixLength(int prefixLength) {
        principalDto.setPrefixLength(prefixLength);
        return this;
    }

}
