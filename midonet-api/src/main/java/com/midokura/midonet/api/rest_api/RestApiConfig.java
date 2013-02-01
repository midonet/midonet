/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.rest_api;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;

/**
 * Config interface for Midolman REST API.
 */
@ConfigGroup(RestApiConfig.GROUP_NAME)
public interface RestApiConfig {

    String GROUP_NAME = "rest_api";

    public static final String BASE_URI = "base_uri";

    @ConfigString(key = BASE_URI)
    public String getBaseUri();

}
