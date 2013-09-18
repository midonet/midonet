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

    public static final String ADMIN_TOKEN = "admin_token";
    public static final String SERVICE_PROTOCOL_KEY = "service_protocol";
    public static final String SERVICE_HOST_kEY = "service_host";
    public static final String SERVICE_PORT_KEY = "service_port";
    public static final String TENANT_NAME = "tenant_name";

    @ConfigString(key = ADMIN_TOKEN, defaultValue = "")
    public String getAdminToken();

    @ConfigString(key = SERVICE_PROTOCOL_KEY, defaultValue = "http")
    public String getServiceProtocol();

    @ConfigString(key = SERVICE_HOST_kEY, defaultValue = "localhost")
    public String getServiceHost();

    @ConfigInt(key = SERVICE_PORT_KEY, defaultValue = 35357)
    public int getServicePort();

    @ConfigString(key = TENANT_NAME, defaultValue = "admin")
    public String getAdminName();

}
