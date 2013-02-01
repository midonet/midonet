/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * Class that defines roles.
 */
public class AuthRole {

    /**
     * Super admin role of the system.
     */
    public static final String ADMIN = "admin";

    /**
     * Tenant admin role.
     */
    public static final String TENANT_ADMIN = "tenant_admin";

    /**
     * Tenant user role.
     */
    public static final String TENANT_USER = "tenant_user";

    /**
     * Checks whether a given role is valid.
     *
     * @param role
     *            Role to check
     * @return True if role is valid
     */
    public static boolean isValidRole(String role) {
        if (role == null) {
            return false;
        }
        String lowerCaseRole = role.toLowerCase();
        return (lowerCaseRole.equals(ADMIN)
                || lowerCaseRole.equals(TENANT_ADMIN) || lowerCaseRole
                    .equals(TENANT_USER));
    }
}
