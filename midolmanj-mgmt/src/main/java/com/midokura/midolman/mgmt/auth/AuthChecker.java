/*
 * @(#)AuthChecker        1.6 12/1/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

/**
 * Class to check authorization.
 *
 * @version 1.6 8 Jan 2012
 * @author Ryu Ishimoto
 */
public class AuthChecker {

    private AuthChecker() {
    }

    /**
     * Checks whether the user sending the request is admin.
     *
     * @param context
     *            Request context.
     * @return True if admin.
     */
    public static boolean isAdmin(SecurityContext context) {
        return (context.isUserInRole(AuthRole.ADMIN.toString()));
    }

    /**
     * Checks whether the user sending the request is service provider.
     *
     * @param context
     *            Request context.
     * @return True if provider.
     */
    public static boolean isProvider(SecurityContext context) {
        return (isAdmin(context) || context.isUserInRole(AuthRole.PROVIDER
                .toString()));
    }

    /**
     * Checks whether the user principal ID passed in matches that of the
     * request. Always returns true for admin.
     *
     * @param id
     *            User principal ID
     * @return True if the requester ID matches the ID.
     */
    public static boolean isUserPrincipal(SecurityContext context, String id) {
        return (isAdmin(context))
                || context.getUserPrincipal().getName().equals(id);
    }

    /**
     * Checks whether the user principal ID passed in matches that of the
     * request. Always returns true for admin.
     *
     * @param id
     *            User principal ID
     * @return True if the requester ID matches the ID.
     */
    public static boolean isUserPrincipal(SecurityContext context, UUID id) {
        return isUserPrincipal(context, id.toString());
    }
}
