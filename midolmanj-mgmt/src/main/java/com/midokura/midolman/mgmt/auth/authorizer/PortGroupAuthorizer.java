/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.authorizer;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for port group
 */
public class PortGroupAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupAuthorizer.class);

    private final PortGroupDao portGroupDao;

    @Inject
    public PortGroupAuthorizer(PortGroupDao portGroupDao) {
        this.portGroupDao = portGroupDao;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException {
        log.debug("PortGroupAuthorizer.authorize entered: id=" + id
                + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        try {
            PortGroup portGroup = portGroupDao.get(id);
            return isOwner(context, portGroup.getTenantId());
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
