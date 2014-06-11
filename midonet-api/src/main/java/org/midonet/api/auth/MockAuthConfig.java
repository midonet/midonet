/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * Config interface for mock auth.
 */
@ConfigGroup(MockAuthConfig.GROUP_NAME)
public interface MockAuthConfig {

    String GROUP_NAME = "mock_auth";

    String ADMIN_TOKEN_KEY = "admin_token";
    String TENANT_ADMIN_TOKEN_KEY = "tenant_admin_token";
    String TENANT_USER_TOKEN_KEY = "tenant_user_token";

    @ConfigString(key = ADMIN_TOKEN_KEY, defaultValue = "")
    String getAdminToken();

    @ConfigString(key = TENANT_ADMIN_TOKEN_KEY, defaultValue = "")
    String getTenantAdminToken();

    @ConfigString(key = TENANT_USER_TOKEN_KEY, defaultValue = "")
    String getTenantUserToken();

}
