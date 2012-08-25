/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.authorizer;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
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

    private final RouterDao routerDao;

    @Inject
    public RouterAuthorizer(RouterDao routerDao) {
        this.routerDao = routerDao;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("RouterAuthorizer.authorize entered: id=" + id
                + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        try {
            Router router = routerDao.get(id);
            return isOwner(context, router.getTenantId());
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
