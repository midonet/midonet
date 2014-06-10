/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.midonet.api.network.VTEPBinding;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract public class AbstractVtepResource extends AbstractResource {

    protected final VtepClusterClient vtepClient;
    protected final ResourceFactory factory;

    @Inject
    public AbstractVtepResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, Validator validator,
                               DataClient dataClient, ResourceFactory factory,
                               VtepClusterClient vtepClient) {
        super(config, uriInfo, context, dataClient, validator);
        this.vtepClient = vtepClient;
        this.factory = factory;
    }

    protected org.midonet.cluster.data.VTEP getVtepOrThrow(
            String ipAddrStr, boolean badRequest)
            throws StateAccessException, SerializationException
    {
        return vtepClient.getVtepOrThrow(parseIPv4Addr(ipAddrStr), badRequest);
    }

    protected final List<VTEPBinding> listVtepBindings(String ipAddrStr,
                                                       UUID bridgeId)
            throws SerializationException, StateAccessException {
        List<VTEPBinding> bindings = vtepClient.listVtepBindings(
                parseIPv4Addr(ipAddrStr), bridgeId);
        URI baseUri = getBaseUri();
        for (VTEPBinding binding : bindings) {
            binding.setBaseUri(baseUri);
        }
        return bindings;
    }


}
