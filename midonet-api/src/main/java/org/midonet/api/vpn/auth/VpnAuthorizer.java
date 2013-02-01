/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.vpn.auth;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.Authorizer;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.VPN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for VPN
 */
public class VpnAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(VpnAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public VpnAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        VPN vpn = dataClient.vpnGet(id);
        if (vpn == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }

        Port port = dataClient.portsGet(vpn.getPublicPortId());

        // VPN ports are on routers
        Router router = dataClient.routersGet(port.getDeviceId());

        String tenantId = router.getProperty(Router.Property.tenant_id);
        if (tenantId == null) {
            log.warn("Attempted to authorize VPN {} of a router {} with " +
                    "no tenant data", id, router.getId());
            return false;
        }

        return isOwner(context, tenantId);
    }
}
