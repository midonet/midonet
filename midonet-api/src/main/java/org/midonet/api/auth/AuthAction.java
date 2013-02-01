/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * Enum that defines auth actions. Authorization is still incomplete so this is
 * just a simplified list of operations we check.
 */
public enum AuthAction {

    READ, WRITE;

}
