/*
 * Copyright 2013 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.cloudstack;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;
import com.midokura.midolman.mgmt.auth.AuthConfig;

/**
 * Config interface for CloudStack auth.
 */
@ConfigGroup(CloudStackConfig.GROUP_NAME)
public interface CloudStackConfig extends AuthConfig {

    String GROUP_NAME = "cloudstack";

    public static final String API_BASE_URI = "api_base_uri";
    public static final String API_PATH = "api_path";
    public static final String API_KEY = "api_key";
    public static final String SECRET_KEY = "secret_key";

    /**
     * The base URI of the API.  It should not end with '/'
     * TODO: Make it more robust
     */
    @ConfigString(key = API_BASE_URI, defaultValue = "http://localhost:8080")
    public String getApiBaseUri();

    /**
     * The API path.  It must begin with '/' and end with '?'
     * TODO: Make it more robust
     */
    @ConfigString(key = API_PATH, defaultValue = "/client/api?")
    public String getApiPath();

    @ConfigString(key = API_KEY, defaultValue = "")
    public String getApiKey();

    @ConfigString(key = SECRET_KEY, defaultValue = "")
    public String getSecretKey();

}
