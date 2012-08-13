/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;

/**
 * Config interface for Midolman REST API.
 */
@ConfigGroup(RestApiConfig.GROUP_NAME)
public interface RestApiConfig {

    String GROUP_NAME = "rest_api";

    @ConfigString(key = "version", defaultValue = "1")
    public String getVersion();


}
