/*
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * Config interface for auth.
 */
@ConfigGroup(AuthConfig.GROUP_NAME)
public interface AuthConfig {

    String GROUP_NAME = "auth";

    String AUTH_PROVIDER = "auth_provider";
    String ADMIN_ROLE_KEY = "admin_role";
    String TENANT_ADMIN_ROLE_KEY = "tenant_admin_role";
    String TENANT_USER_ROLE_KEY = "tenant_user_role";

    @ConfigString(key = AUTH_PROVIDER,
            defaultValue = "org.midonet.api.auth.MockAuthService")
    String getAuthProvider();

    @ConfigString(key = ADMIN_ROLE_KEY, defaultValue = "mido_admin")
    String getAdminRole();

    @ConfigString(key = TENANT_ADMIN_ROLE_KEY,
            defaultValue = "mido_tenant_admin")
    String getTenantAdminRole();

    @ConfigString(key = TENANT_USER_ROLE_KEY,
            defaultValue = "mido_tenant_user")
    String getTenantUserRole();

}
