/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import com.midokura.config.ConfigBool;
import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;

/**
 * Config interface for auth.
 */
@ConfigGroup(AuthConfig.GROUP_NAME)
public interface AuthConfig {

    String GROUP_NAME = "auth";

    public static final String USE_MOCK_KEY = "use_mock";
    public static final String ADMIN_TOKEN_KEY = "admin_token";
    public static final String TENANT_ADMIN_TOKEN_KEY = "tenant_admin_token";
    public static final String TENANT_USER_TOKEN_KEY = "tenant_user_token";
    public static final String ADMIN_ROLE_KEY = "admin_role";
    public static final String TENANT_ADMIN_ROLE_KEY = "tenant_admin_role";
    public static final String TENANT_USER_ROLE_KEY = "tenant_user_role";


    @ConfigBool(key = USE_MOCK_KEY, defaultValue = false)
    public boolean getUseMock();

    @ConfigString(key = ADMIN_TOKEN_KEY, defaultValue = "")
    public String getAdminToken();

    @ConfigString(key = TENANT_ADMIN_TOKEN_KEY, defaultValue = "")
    public String getTenantAdminToken();

    @ConfigString(key = TENANT_USER_TOKEN_KEY, defaultValue = "")
    public String getTenantUserToken();

    @ConfigString(key = ADMIN_ROLE_KEY, defaultValue = "mido_admin")
    public String getAdminRole();

    @ConfigString(key = TENANT_ADMIN_ROLE_KEY,
            defaultValue = "mido_tenant_admin")
    public String getTenantAdminRole();

    @ConfigString(key = TENANT_USER_ROLE_KEY,
            defaultValue = "mido_tenant_user")
    public String getTenantUserRole();

}
