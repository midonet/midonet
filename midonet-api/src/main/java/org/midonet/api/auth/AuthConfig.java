/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
