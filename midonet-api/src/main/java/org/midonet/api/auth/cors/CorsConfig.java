/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.cors;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * Config interface for CORS
 */
@ConfigGroup(CorsConfig.GROUP_NAME)
public interface CorsConfig {

    String GROUP_NAME = "cors";

    public static final String ALLOW_ORIGIN_KEY =
            "access_control_allow_origin";
    public static final String ALLOW_HEADERS_KEY =
            "access_control_allow_headers";
    public static final String ALLOW_METHODS_KEY =
            "access_control_allow_methods";
    public static final String EXPOSE_HEADERS_KEY =
            "access_control_expose_headers";

    @ConfigString(key = ALLOW_ORIGIN_KEY, defaultValue = "*")
    public String getAccessControlAllowOrigin();

    @ConfigString(key = ALLOW_HEADERS_KEY,
            defaultValue = "Origin, X-Auth-Token, Content-Type, Accept")
    public String getAccessControlAllowHeaders();

    @ConfigString(key = ALLOW_METHODS_KEY,
            defaultValue = "GET, POST, PUT, DELETE, OPTIONS")
    public String getAccessControlAllowMethods();

    @ConfigString(key = EXPOSE_HEADERS_KEY, defaultValue = "Location")
    public String getAccessControlExposeHeaders();

}
