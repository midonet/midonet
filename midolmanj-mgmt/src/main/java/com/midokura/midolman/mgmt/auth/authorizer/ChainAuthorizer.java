/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.authorizer;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for Chain
 */
public class ChainAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(ChainAuthorizer.class);

    private final ChainDao chainDao;

    @Inject
    public ChainAuthorizer(ChainDao chainDao) {
        this.chainDao = chainDao;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("ChainAuthorizer.authorize entered: id=" + id
                + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        try {
            Chain chain = chainDao.get(id);
            return isOwner(context, chain.getTenantId());
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
