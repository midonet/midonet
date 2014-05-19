/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.auth.vsphere;

import org.midonet.api.auth.AuthConfig;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * Config interface for vSphere.
 */
@ConfigGroup(VSphereConfig.GROUP_NAME)
public interface VSphereConfig extends AuthConfig {

    String GROUP_NAME = "vsphere";

    public static final String SERVICE_SDK_URL = "sdk_url";
    public static final String SERVICE_DC_ID= "service_datacenter_id";
    public static final String IGNORE_SERVER_CERT = "ignore_server_cert";
    public static final String SERVICE_SSL_CERT_SHA1_FINGERPRINT =
            "service_ssl_cert_fingerprint";
    /**
     * The server sdk url (eg: https://localhost/sdk)
     */
    @ConfigString(key = SERVICE_SDK_URL)
    public String getServiceSdkUrl();

    /**
     * The datacenter ID. The datacenter will be used as base object to check
     * permissions on
     */
    @ConfigString(key = SERVICE_DC_ID)
    public String getServiceDCId();

    /**
     * If "true" the server certificate validation will be turned off.
     * This means that every server certificate will considered VALID.
     * Please DO NOT ENABLE this in production, it's not a good idea
     */
    @ConfigString(key = IGNORE_SERVER_CERT, defaultValue = "false")
    public String ignoreServerCert();

    /**
     *  the server certificate fingerprint in the form of an hex SHA-1
     *  hash, eg: "20:4D:FB:4E:07:D8:E3:7F:67:AD:93:1A:8A:64:65:49:12:E8:50:88"
     */
    @ConfigString(key = SERVICE_SSL_CERT_SHA1_FINGERPRINT, defaultValue = "")
    public String getServiceSSLCertFingerprint();
}