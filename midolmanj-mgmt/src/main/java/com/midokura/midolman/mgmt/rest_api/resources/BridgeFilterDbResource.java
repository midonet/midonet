/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.FilteringDbEntry;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

@RequestScoped
public class BridgeFilterDbResource {

    private final UUID bridgeId;

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final PortDao portDao;

    @Inject
    public BridgeFilterDbResource(UriInfo uriInfo,
                                  SecurityContext context,
                                  Authorizer authorizer,
                                  PortDao portDao,
                                  @Assisted UUID bridgeId) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.portDao = portDao;
        this.bridgeId = bridgeId;
    }

    /**
     * Handler to list filtering database static entries.
     *
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A list of FilteringDbEntry objects.
     */
    @GET
    @PermitAll
    @Produces({ MediaType.APPLICATION_JSON })
    public List<FilteringDbEntry> list() throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these ports.");
        }

        List<Port> ports = portDao.findByBridge(bridgeId);
        if (ports != null) {
            for (UriResource resource : ports) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return null;
    }
}
