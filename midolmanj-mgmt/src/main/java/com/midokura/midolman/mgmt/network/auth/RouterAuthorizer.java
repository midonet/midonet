/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network.auth;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for Router
 */
public class RouterAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(RouterAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public RouterAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        Router router = dataClient.routersGet(id);
        if (router == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } else {
            return isOwner(context, router.getProperty(
                    Router.Property.tenant_id));
        }
    }
}
