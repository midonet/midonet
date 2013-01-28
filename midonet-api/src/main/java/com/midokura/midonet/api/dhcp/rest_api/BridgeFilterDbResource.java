/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midonet.api.dhcp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midonet.api.auth.AuthAction;
import com.midokura.midonet.api.auth.Authorizer;
import com.midokura.midonet.api.auth.ForbiddenHttpException;
import com.midokura.midonet.api.network.Port;
import com.midokura.midonet.api.network.PortFactory;
import com.midokura.midonet.api.network.auth.BridgeAuthorizer;
import com.midokura.midonet.api.rest_api.AbstractResource;
import com.midokura.midonet.api.rest_api.RestApiConfig;
import com.midokura.midonet.api.dhcp.FilteringDbEntry;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequestScoped
public class BridgeFilterDbResource extends AbstractResource {

    private final UUID bridgeId;

    private final Authorizer authorizer;
    private final DataClient dataClient;

    @Inject
    public BridgeFilterDbResource(RestApiConfig config, UriInfo uriInfo,
                                  SecurityContext context,
                                  BridgeAuthorizer authorizer,
                                  DataClient dataClient,
                                  @Assisted UUID bridgeId) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.dataClient = dataClient;
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

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these ports.");
        }


        List<com.midokura.midonet.cluster.data.Port<?,?>> portConfigs =
                dataClient.portsFindByBridge(bridgeId);

        List<Port> ports = new ArrayList<Port>();
        for(com.midokura.midonet.cluster.data.Port<?,?> portConfig :
                portConfigs) {
            Port port = PortFactory.createPort(portConfig);
            port.setBaseUri(getBaseUri());
            ports.add(port);
        }

        return null;
    }
}
