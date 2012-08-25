/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.authorizer;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
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

    private final PortDao portDao;
    private final RouterDao routerDao;
    private final BridgeDao bridgeDao;

    @Inject
    public PortAuthorizer(PortDao portDao, RouterDao routerDao,
                          BridgeDao bridgeDao) {
        this.portDao = portDao;
        this.routerDao = routerDao;
        this.bridgeDao = bridgeDao;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("PortAuthorizer.authorize entered: id=" + id
                + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        try {
            String tenantId = null;
            Port port = portDao.get(id);
            if (port.isRouterPort()) {
                Router router = routerDao.get(port.getDeviceId());
                tenantId = router.getTenantId();
            } else {
                Bridge bridge =  bridgeDao.get(port.getDeviceId());
                tenantId = bridge.getTenantId();
            }
            return isOwner(context, tenantId);
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
