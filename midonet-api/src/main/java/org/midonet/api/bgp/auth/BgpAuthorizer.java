/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.bgp.auth;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.Authorizer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for BGP
 */
public class BgpAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(BgpAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public BgpAuthorizer(DataClient dataClient) {
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

        BGP bgp = dataClient.bgpGet(id);
        if (bgp == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }

        Port<?, ?> port = dataClient.portsGet(bgp.getPortId());

        // Must be a router port
        Router router = dataClient.routersGet(port.getDeviceId());

        String tenantId = router.getProperty(Router.Property.tenant_id);
        if (tenantId == null) {
            log.warn("Attempted to authorize BGP {} of a router {} with " +
                    "no tenant data", id, router.getId());
            return false;
        }

        return isOwner(context, tenantId);
    }
}
