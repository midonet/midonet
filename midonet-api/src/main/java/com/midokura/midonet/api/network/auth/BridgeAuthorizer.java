/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.auth;

import com.google.inject.Inject;
import com.midokura.midonet.api.auth.AuthAction;
import com.midokura.midonet.api.auth.Authorizer;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.Bridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for Bridge
 */
public class BridgeAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public BridgeAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        Bridge bridge = dataClient.bridgesGet(id);
        if (bridge == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } else {
            return isOwner(context, bridge.getProperty(
                    Bridge.Property.tenant_id));
        }
    }
}
