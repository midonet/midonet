/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.cloudstack;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;
import org.midonet.api.auth.AuthConfig;

/**
 * Config interface for CloudStack auth.
 */
@ConfigGroup(CloudStackConfig.GROUP_NAME)
public interface CloudStackConfig extends AuthConfig {

    String GROUP_NAME = "cloudstack";

    String API_BASE_URI = "api_base_uri";
    String API_PATH = "api_path";
    String API_KEY = "api_key";
    String SECRET_KEY = "secret_key";

    /**
     * The base URI of the API.  It should not end with '/'
     * TODO: Make it more robust
     */
    @ConfigString(key = API_BASE_URI, defaultValue = "http://localhost:8080")
    String getApiBaseUri();

    /**
     * The API path.  It must begin with '/' and end with '?'
     * TODO: Make it more robust
     */
    @ConfigString(key = API_PATH, defaultValue = "/client/api?")
    String getApiPath();

    @ConfigString(key = API_KEY, defaultValue = "")
    String getApiKey();

    @ConfigString(key = SECRET_KEY, defaultValue = "")
    String getSecretKey();
}
