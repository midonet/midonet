/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;

/**
 * Config interface for Midolman REST API.
 */
@ConfigGroup(RestApiConfig.GROUP_NAME)
public interface RestApiConfig {

    String GROUP_NAME = "rest_api";

    public static final String VERSION_KEY = "version";
    public static final String BASE_URI = "base_uri";

    @ConfigString(key = VERSION_KEY, defaultValue = "1")
    public String getVersion();

    @ConfigString(key = BASE_URI)
    public String getBaseUri();

}
