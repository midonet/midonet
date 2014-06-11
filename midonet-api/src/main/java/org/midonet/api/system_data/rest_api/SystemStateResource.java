/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.system_data.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.system_data.SystemState;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

/**
 * Root Resource class for System State Data
 */
@RequestScoped
public class SystemStateResource extends AbstractResource {

    @Inject
    public SystemStateResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, DataClient dataClient) {
        super(config, uriInfo, context, dataClient);
    }

    /**
     * Handler for GET requests to the system state data
     *
     * @return The system state info
     * @throws StateAccessException
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_SYSTEM_STATE_JSON,
                  MediaType.APPLICATION_JSON})
    public SystemState get()
        throws StateAccessException {
        org.midonet.cluster.data.SystemState systemStateData =
                dataClient.systemStateGet();
        SystemState systemState = new SystemState(systemStateData);
        systemState.setBaseUri(getBaseUri());
        return systemState;
    }

    /**
     * Handler for PUT requests to the system state data
     *
     * @param systemState the new System State info
     * @throws StateAccessException
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_SYSTEM_STATE_JSON,
            MediaType.APPLICATION_JSON})
    public void update(SystemState systemState)
            throws StateAccessException {
        dataClient.systemStateUpdate(systemState.toData());
    }
}
