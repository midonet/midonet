/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * Config interface for Midolman REST API.
 */
@ConfigGroup(RestApiConfig.GROUP_NAME)
public interface RestApiConfig {

    String GROUP_NAME = "rest_api";

    String BASE_URI = "base_uri";

    @ConfigString(key = BASE_URI)
    String getBaseUri();

}
