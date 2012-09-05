/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.net.URI;

import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoPort;

/**
 * Abstract port resource class for bridge port and router port
 *
 * @param <T> type of the resource
 * @param <U> type of the dto for the resource
 */
public abstract class Port<T, U extends DtoPort> extends ResourceBase<T, U> {

    public Port(WebResource resource, URI uriForCreation,
                U principalDto, String mediaType) {
        super(resource, uriForCreation, principalDto, mediaType);
    }
}

