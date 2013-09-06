/*
 * Copyright (c) 2013 Midokura Pte.Ltd.
 */

package org.midonet.api.system_data.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.system_data.WriteVersion;
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
 * Root Resource class for Write Version Data
 */
@RequestScoped
public class WriteVersionResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(WriteVersionResource.class);

    private final DataClient dataClient;

    @Inject
    public WriteVersionResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, DataClient dataClient) {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
    }

    /**
     * Handler for returning the write version info
     *
     * @return List of trace messages associated with the given trace ID
     * @throws StateAccessException
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_WRITE_VERSION_JSON,
                  MediaType.APPLICATION_JSON})
    public WriteVersion get()
        throws StateAccessException {
        org.midonet.cluster.data.WriteVersion writeVersionData =
                dataClient.writeVersionGet();
        WriteVersion writeVersion = new WriteVersion(writeVersionData);
        writeVersion.setBaseUri(getBaseUri());
        return writeVersion;
    }

    /**
     * Handler for updating the write version info.
     *
     * @param writeVersion the new write version info
     * @throws StateAccessException
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_WRITE_VERSION_JSON,
            MediaType.APPLICATION_JSON})
    public void update(WriteVersion writeVersion)
            throws StateAccessException {
        dataClient.writeVersionUpdate(writeVersion.toData());
    }
}
