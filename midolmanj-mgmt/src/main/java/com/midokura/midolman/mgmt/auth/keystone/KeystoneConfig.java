/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.keystone;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;
import com.midokura.midolman.mgmt.auth.AuthConfig;

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

    @ConfigString(key = ADMIN_TOKEN, defaultValue = "")
    public String getAdminToken();

    @ConfigString(key = SERVICE_PROTOCOL_KEY, defaultValue = "http")
    public String getServiceProtocol();

    @ConfigString(key = SERVICE_HOST_kEY, defaultValue = "localhost")
    public String getServiceHost();

    @ConfigInt(key = SERVICE_PORT_KEY, defaultValue = 35357)
    public int getServicePort();

}
