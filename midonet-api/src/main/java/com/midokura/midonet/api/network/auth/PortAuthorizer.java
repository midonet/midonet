/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.auth;

import com.google.inject.Inject;
import com.midokura.midonet.api.auth.AuthAction;
import com.midokura.midonet.api.auth.Authorizer;
import com.midokura.midonet.api.network.Port;
import com.midokura.midonet.api.network.PortFactory;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for Port
 */
public class PortAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(PortAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public PortAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        try {
            String tenantId = null;
            com.midokura.midonet.cluster.data.Port portData =
                    dataClient.portsGet(id);
            if (portData == null) {
                log.warn("Attempted to authorize a non-existent resource: {}",
                        id);
                return false;
            }

            Port port = PortFactory.createPort(portData);
            if (port.isRouterPort()) {
                Router router = dataClient.routersGet(port.getDeviceId());
                tenantId = router.getProperty(Router.Property.tenant_id);
            } else {
                Bridge bridge =  dataClient.bridgesGet(port.getDeviceId());
                tenantId = bridge.getProperty(Bridge.Property.tenant_id);
            }
            return isOwner(context, tenantId);
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
