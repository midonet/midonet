/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.auth;

import com.google.inject.Inject;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.AuthAction;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for route
 */
public class RouteAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(RouteAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public RouteAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException,
                                             SerializationException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        Route route = dataClient.routesGet(id);
        if (route == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }

        Router router = dataClient.routersGet(route.getRouterId());
        String tenantId = router.getProperty(Router.Property.tenant_id);
        if (tenantId == null) {
            log.warn("Attempted to authorize route {} of a router {} with " +
                    "no tenant data", id, router.getId());
            return false;
        }

        return isOwner(context, tenantId);
    }
}
