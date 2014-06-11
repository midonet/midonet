/*
 * Copyright 2012 Midokura PTE LTD.
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

    String ADMIN_TOKEN = "admin_token";
    String SERVICE_PROTOCOL_KEY = "service_protocol";
    String SERVICE_HOST_kEY = "service_host";
    String SERVICE_PORT_KEY = "service_port";
    String TENANT_NAME = "tenant_name";

    @ConfigString(key = ADMIN_TOKEN, defaultValue = "")
    String getAdminToken();

    @ConfigString(key = SERVICE_PROTOCOL_KEY, defaultValue = "http")
    String getServiceProtocol();

    @ConfigString(key = SERVICE_HOST_kEY, defaultValue = "localhost")
    String getServiceHost();

    @ConfigInt(key = SERVICE_PORT_KEY, defaultValue = 35357)
    int getServicePort();

    @ConfigString(key = TENANT_NAME, defaultValue = "admin")
    String getAdminName();
}
