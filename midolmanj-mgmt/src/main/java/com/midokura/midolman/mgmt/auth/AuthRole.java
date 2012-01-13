/*
 * @(#)AuthRole.java        1.6 12/1/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

/**
 * Enum that defines user roles.
 *
 * @version 1.6 8 Jan 2012
 * @author Ryu Ishimoto
 */
public enum AuthRole {

    ADMIN("Admin"), PROVIDER("Provider");

    private final String value;

    private AuthRole(String val) {
        this.value = val;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Enum#toString()
     */
    @Override
    public String toString() {
        return value;
    }
}
