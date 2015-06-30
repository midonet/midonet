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

import org.midonet.api.auth.AuthConfig;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;

/**
 * Config interface for Keystone.
 */
@ConfigGroup(KeystoneConfig.GROUP_NAME)
@Deprecated
public interface KeystoneConfig extends AuthConfig {

    String GROUP_NAME = "keystone";

    String ADMIN_TOKEN = "admin_token";
    String SERVICE_PROTOCOL_KEY = "service_protocol";
    String SERVICE_HOST_KEY = "service_host";
    String SERVICE_PORT_KEY = "service_port";
    String TENANT_NAME = "tenant_name";

    @ConfigString(key = ADMIN_TOKEN, defaultValue = "")
    String getAdminToken();

    @ConfigString(key = SERVICE_PROTOCOL_KEY, defaultValue = "http")
    String getServiceProtocol();

    @ConfigString(key = SERVICE_HOST_KEY, defaultValue = "localhost")
    String getServiceHost();

    @ConfigInt(key = SERVICE_PORT_KEY, defaultValue = 35357)
    int getServicePort();

    @ConfigString(key = TENANT_NAME, defaultValue = "admin")
    String getAdminName();
}
