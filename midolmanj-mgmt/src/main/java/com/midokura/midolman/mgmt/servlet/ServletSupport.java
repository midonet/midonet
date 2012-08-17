/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.servlet;

import com.midokura.midolman.mgmt.auth.UserIdentity;

/**
 * Contains support for anything servlet related.
 */
public class ServletSupport {

    /**
     * User identity key
     */
    public static final String USER_IDENTITY_ATTR_KEY =
            UserIdentity.class.getName();

    /**
     * Config key for auth client
     */
    public static final String AUTH_CLIENT_CONFIG_KEY = "auth_client";

}
