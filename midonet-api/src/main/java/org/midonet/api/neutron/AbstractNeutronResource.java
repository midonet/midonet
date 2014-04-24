/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.data.neutron.NeutronPlugin;

import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

public abstract class AbstractNeutronResource extends AbstractResource {

    // Hide the parent class's dataClient variable and replace it with
    // NeutronPlugin.
    protected NeutronPlugin dataClient;

    public AbstractNeutronResource(RestApiConfig config, UriInfo uriInfo,
                                   SecurityContext context) {
        this(config, uriInfo, context, null);
    }

    public AbstractNeutronResource(RestApiConfig config, UriInfo uriInfo,
                                   SecurityContext context,
                                   NeutronPlugin plugin) {
        this(config, uriInfo, context, plugin, null);
    }

    public AbstractNeutronResource(RestApiConfig config, UriInfo uriInfo,
                                   SecurityContext context,
                                   NeutronPlugin plugin,
                                   Validator validator) {
        super(config, uriInfo, context, plugin, validator);
        this.dataClient = plugin;
    }
}
