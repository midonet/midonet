/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.authorizer;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for AdRoute
 */
public class AdRouteAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(AdRouteAuthorizer.class);

    private final RouterDao routerDao;
    private final BgpDao bgpDao;

    @Inject
    public AdRouteAuthorizer(RouterDao routerDao, BgpDao bgpDao) {
        this.routerDao = routerDao;
        this.bgpDao = bgpDao;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("AdRouteAuthorizer.authorize entered: id=" + id
                + ", action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        try {
            Bgp bgp = bgpDao.findByAdRoute(id);
            Router router = routerDao.findByBgp(bgp.getId());
            return isOwner(context, router.getTenantId());
        } catch (NoStatePathException ex) {
            // findBy* throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
