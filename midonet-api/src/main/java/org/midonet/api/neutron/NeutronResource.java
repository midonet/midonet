/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.Neutron;
import org.midonet.client.neutron.NeutronMediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;


public class NeutronResource extends AbstractNeutronResource {

    private final static Logger log = LoggerFactory.getLogger(
            NeutronResource.class);

    private final ResourceFactory factory;

    @Inject
    public NeutronResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context, ResourceFactory factory) {
        super(config, uriInfo, context);
        this.factory = factory;
    }

    @Path(NeutronUriBuilder.NETWORKS)
    public NetworkResource getNetworkResource() {
        return factory.getNeutronNetworkResource();
    }

    /**
     * Handler to getting a neutron object.
     *
     * @return A Neutron object.
     */
    @GET
    @RolesAllowed(AuthRole.ADMIN)
    @Produces(NeutronMediaType.NEUTRON_JSON_V1)
    public Neutron get() {

        Neutron neutron = new Neutron();

        URI baseUri = getBaseUri();
        neutron.uri = NeutronUriBuilder.getNeutron(baseUri);
        neutron.networks = NeutronUriBuilder.getNetworks(baseUri);
        neutron.networkTemplate = NeutronUriBuilder.getNetworkTemplate(
                baseUri);
        return neutron;

    }
}
