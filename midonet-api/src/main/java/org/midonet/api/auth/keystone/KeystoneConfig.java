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
package org.midonet.api.auth.keystone;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;
import org.midonet.api.auth.AuthConfig;

/**
 * Config interface for Keystone.
 */
@ConfigGroup(KeystoneConfig.GROUP_NAME)
public interface KeystoneConfig extends AuthConfig {

    String GROUP_NAME = "keystone";

    String ADMIN_TOKEN_KEY = "admin_token";
    String DOMAIN_NAME_KEY = "domain_name";
    String PASSWORD_KEY = "user_password";
    String SERVICE_PROTOCOL_KEY = "service_protocol";
    String SERVICE_HOST_KEY = "service_host";
    String SERVICE_PORT_KEY = "service_port";
    String TENANT_NAME_KEY = "tenant_name";
    String USER_NAME_KEY = "user_name";
    String VERSION_KEY = "version";

    @ConfigString(key = ADMIN_TOKEN_KEY, defaultValue = "")
    String getAdminToken();

    @ConfigString(key = DOMAIN_NAME_KEY, defaultValue = "default")
    String getDomainName();

    @ConfigString(key = PASSWORD_KEY, defaultValue = "")
    String getPassword();

    @ConfigString(key = TENANT_NAME_KEY, defaultValue = "admin")
    String getProjectName();

    @ConfigString(key = SERVICE_HOST_KEY, defaultValue = "localhost")
    String getServiceHost();

    @ConfigInt(key = SERVICE_PORT_KEY, defaultValue = 35357)
    int getServicePort();

    @ConfigString(key = SERVICE_PROTOCOL_KEY, defaultValue = "http")
    String getServiceProtocol();

    @ConfigString(key = USER_NAME_KEY, defaultValue = "")
    String getUserName();

    @ConfigInt(key = VERSION_KEY, defaultValue = 2)
    int getVersion();

}
