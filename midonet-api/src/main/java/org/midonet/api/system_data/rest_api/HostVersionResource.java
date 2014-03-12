/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.system_data.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.system_data.HostVersion;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root Resource class for System State Data
 */
@RequestScoped
public class HostVersionResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(HostVersionResource.class);

    @Inject
    public HostVersionResource(RestApiConfig config, UriInfo uriInfo,
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
    @Produces({VendorMediaType.APPLICATION_HOST_VERSION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<HostVersion> get()
        throws StateAccessException {
        List<org.midonet.cluster.data.HostVersion> hostVersionsData =
                dataClient.hostVersionsGet();
        List<HostVersion> hostVersionList =
                new ArrayList<>(hostVersionsData.size());
        if (hostVersionsData != null) {
            for (org.midonet.cluster.data.HostVersion hostVersionData :
                    hostVersionsData) {
                HostVersion hostVersion = new HostVersion(hostVersionData);
                hostVersion.setHost(ResourceUriBuilder.getHost(
                        getBaseUri(), hostVersion.getHostId()));
                hostVersionList.add(hostVersion);
            }
        }
        return hostVersionList;
    }
}
