/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.auth;

import java.util.UUID;
import javax.ws.rs.core.SecurityContext;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.Authorizer;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.VlanAwareBridge;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authorizer for VlanBridge
 */
public class VlanBridgeAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(VlanBridgeAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public VlanBridgeAuthorizer(DataClient dataClient) {
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

        VlanAwareBridge vlanBridge = dataClient.vlanBridgesGet(id);
        if (vlanBridge == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } else {
            return isOwner(context, vlanBridge.getProperty(
                                           VlanAwareBridge.Property.tenant_id));
        }
    }
}
