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
package org.midonet.cluster.auth;

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
