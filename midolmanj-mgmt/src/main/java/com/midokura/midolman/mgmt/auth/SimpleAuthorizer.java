/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.StateAccessException;

/**
 * Simple authorization service.
 */
public class SimpleAuthorizer implements Authorizer {

    private final static Logger log = LoggerFactory
            .getLogger(SimpleAuthorizer.class);
    private final TenantDao tenantDao;

    /**
     * Constructor
     *
     * @param tenantDao
     *            Tenant DAO.
     */
    public SimpleAuthorizer(TenantDao tenantDao) {
        this.tenantDao = tenantDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#isAdmin(javax.ws.rs.core
     * .SecurityContext)
     */
    @Override
    public boolean isAdmin(SecurityContext context) {
        log.debug("SimpleAuthorizer.isAdmin entered.");
        boolean isAdmin = AuthChecker.isAdmin(context);
        log.debug("SimpleAuthorizer.isAdmin exiting: isAdmin={}", isAdmin);
        return isAdmin;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#adRouteAuthorized(javax.
     * ws.rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean adRouteAuthorized(SecurityContext context,
            AuthAction action, UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.adRouteAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByAdRoute(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.adRouteAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#bgpAuthorized(javax.ws.rs
     * .core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean bgpAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.bgpAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByBgp(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.bgpAuthorized exiting: allowed={}", allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#bridgeAuthorized(javax.ws
     * .rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean bridgeAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.bridgeAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByBridge(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.bridgeAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#chainAuthorized(javax.ws
     * .rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean chainAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.chainAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByChain(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.chainAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    @Override
    public boolean portGroupAuthorized(SecurityContext context,
            AuthAction action, UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.portGroupAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByChain(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.portGroupAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#portAuthorized(javax.ws.
     * rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean portAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.portAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByPort(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.portAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#routeAuthorized(javax.ws
     * .rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean routeAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.routeAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByRoute(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.routeAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#routerLinkAuthorized(javax
     * .ws.rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID, java.util.UUID)
     */
    @Override
    public boolean routerLinkAuthorized(SecurityContext context,
            AuthAction action, UUID id, UUID peerId)
            throws StateAccessException {
        log.debug("SimpleAuthorizer.routerLinkAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }

        // Allow if both routers belong to the user.
        Tenant routerTenant = tenantDao.getByRouter(id);
        if (!AuthChecker.isUserPrincipal(context, routerTenant.getId())) {
            return false;
        }

        Tenant peerRouterTenant = tenantDao.getByRouter(peerId);
        boolean allowed = routerTenant.getId().equals(peerRouterTenant.getId());

        log.debug("SimpleAuthorizer.routerLinkAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    @Override
    public boolean routerBridgeLinkAuthorized(SecurityContext context,
            AuthAction action, UUID routerId, UUID bridgeId)
            throws StateAccessException {

        if (AuthChecker.isAdmin(context)) {
            return true;
        }

        // Allow if both devices belong to the user.
        Tenant routerTenant = tenantDao.getByRouter(routerId);
        if (!AuthChecker.isUserPrincipal(context, routerTenant.getId())) {
            return false;
        }

        Tenant bridgeTenant = tenantDao.getByBridge(bridgeId);
        return routerTenant.getId().equals(bridgeTenant.getId());
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#routerAuthorized(javax.ws
     * .rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean routerAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.routerAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByRouter(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.routerAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#ruleReadAuthorized(javax
     * .ws.rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean ruleAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.ruleAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByRule(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.ruleAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#tenantAuthorized(javax.ws
     * .rs.core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.lang.String)
     */
    @Override
    public boolean tenantAuthorized(SecurityContext context, AuthAction action,
            String id) {
        log.debug("SimpleAuthorizer.tenantAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        boolean allowed = AuthChecker.isUserPrincipal(context, id);

        log.debug("SimpleAuthorizer.tenantAuthorized exiting: allowed={}",
                allowed);
        return allowed;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#vifAuthorized(javax.ws.rs
     * .core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean vifAuthorized(SecurityContext context, AuthAction action,
            UUID portId) throws StateAccessException {
        log.debug("SimpleAuthorizer.vifAuthorized entered: portId=" + portId
                + ",action=" + action);
        return portAuthorized(context, action, portId);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.Authorizable#vpnAuthorized(javax.ws.rs
     * .core.SecurityContext, com.midokura.midolman.mgmt.auth.AuthAction,
     * java.util.UUID)
     */
    @Override
    public boolean vpnAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException {
        log.debug("SimpleAuthorizer.vpnAuthorized entered: id=" + id
                + ",action=" + action);

        if (AuthChecker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByVpn(id);
        boolean allowed = AuthChecker.isUserPrincipal(context, tenant.getId());

        log.debug("SimpleAuthorizer.vpnAuthorized exiting: allowed={}", allowed);
        return allowed;
    }

}
