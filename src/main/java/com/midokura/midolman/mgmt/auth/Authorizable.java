/*
 * @(#)Authorizable        1.6 12/1/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import com.midokura.midolman.state.StateAccessException;

/**
 * Interface for authorization service.
 *
 * @version 1.6 8 Jan 2012
 * @author Ryu Ishimoto
 */
public interface Authorizable {

    /**
     * Checks whether the user is admin.
     *
     * @param context
     *            SecurityContext object.
     * @return True if admin.
     */
    boolean isAdmin(SecurityContext context);

    /**
     * Checks whether the user is authorized to view ad route.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean adRouteAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException;

    /**
     * Checks whether the user is authorized to view BGP.
     *
     * @param context
     *            SecurityContect object.
     * @param id
     *            ID of the object to check.
     * @param action
     *            AuthAction object.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean bgpAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;

    /**
     * Checks whether the user is authorized to view bridge.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean bridgeAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;

    /**
     * Checks whether the user is authorized to view chain.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean chainAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;

    /**
     * Checks whether the user is authorized to view port.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean portAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;

    /**
     * Checks whether the user is authorized to view route.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean routeAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;

    /**
     * Checks whether router linking is allowed.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the router to check.
     * @param peerId
     *            ID of the peer router to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean routerLinkAuthorized(SecurityContext context, AuthAction action,
            UUID id, UUID peerId) throws StateAccessException;

    /**
     * Checks whether the user is authorized to view router.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean routerAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;

    /**
     * Checks whether the user is authorized to view rule.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean ruleReadAuthorized(SecurityContext context, AuthAction action,
            UUID id) throws StateAccessException;

    /**
     * Check whether the given ID is the tenant in the context.
     *
     * @param context
     *            SecurityContext object.
     * @param action
     *            AuthAction object.
     * @param id
     *            Tenant ID to check.
     * @return True if the ID matches the context user principal ID.
     */
    boolean tenantAuthorized(SecurityContext context, AuthAction action,
            String id);

    /**
     * Checks whether VIF plug is allowed.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param portId
     *            ID of the port to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean vifAuthorized(SecurityContext context, AuthAction action,
            UUID portId) throws StateAccessException;

    /**
     * Checks whether the user is authorized to view VPN.
     *
     * @param context
     *            SecurityContect object.
     * @param action
     *            AuthAction object.
     * @param id
     *            ID of the object to check.
     * @return True if authorized.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean vpnAuthorized(SecurityContext context, AuthAction action, UUID id)
            throws StateAccessException;
}
