/*
 * @(#)Authorizer        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
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
 * Authorization service.
 *
 * @version 1.6 11 Jan 2011
 * @author Ryu Ishimoto
 */
public class Authorizer implements Authorizable {

    private final static Logger log = LoggerFactory.getLogger(Authorizer.class);
    private final AuthChecker checker;
    private final TenantDao tenantDao;

    /**
     * Constructor
     *
     * @param checker
     *            Authorization checker class.
     * @param tenantDao
     *            Tenant DAO.
     */
    public Authorizer(AuthChecker checker, TenantDao tenantDao) {
        this.checker = checker;
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
        log.debug("Authorizer.isAdmin entered.");
        boolean isAdmin = checker.isAdmin(context);
        log.debug("Authorizer.isAdmin exiting: isAdmin={}", isAdmin);
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
        log.debug("Authorizer.adRouteAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByAdRoute(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.adRouteAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.bgpAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByBgp(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.bgpAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.bridgeAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByBridge(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.bridgeAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.chainAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByChain(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.chainAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.portAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByPort(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.portAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.routeAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByRoute(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.routeAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.routerLinkAuthorized entered: id=" + id
                + ",action=" + action);

        if (checker.isProvider(context)) {
            return true;
        }

        // Allow if both routers belong to the user.
        Tenant routerTenant = tenantDao.getByRouter(id);
        if (!checker.isUserPrincipal(context, routerTenant.getId())) {
            return false;
        }

        Tenant peerRouterTenant = tenantDao.getByRouter(peerId);
        boolean allowed = routerTenant.getId().equals(peerRouterTenant.getId());

        log.debug("Authorizer.routerLinkAuthorized exiting: allowed={}",
                allowed);
        return allowed;
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
        log.debug("Authorizer.routerAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByRouter(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.routerAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.ruleAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByRule(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.ruleAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.tenantAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        boolean allowed = checker.isUserPrincipal(context, id);

        log.debug("Authorizer.tenantAuthorized exiting: allowed={}", allowed);
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
        log.debug("Authorizer.vifAuthorized entered: portId=" + portId
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
        log.debug("Authorizer.vpnAuthorized entered: id=" + id + ",action="
                + action);

        if (checker.isAdmin(context)) {
            return true;
        }
        Tenant tenant = tenantDao.getByVpn(id);
        boolean allowed = checker.isUserPrincipal(context, tenant.getId());

        log.debug("Authorizer.vpnAuthorized exiting: allowed={}", allowed);
        return allowed;
    }

}
