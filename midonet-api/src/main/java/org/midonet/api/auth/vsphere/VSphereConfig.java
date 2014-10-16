/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

    String ADMIN_TOKEN = "admin_token";
    String SERVICE_SDK_URL = "sdk_url";
    String SERVICE_DC_ID= "service_datacenter_id";
    String IGNORE_SERVER_CERT = "ignore_server_cert";
    String SERVICE_SSL_CERT_SHA1_FINGERPRINT = "service_ssl_cert_fingerprint";
    /**
     * The server sdk url (eg: https://localhost/sdk)
     */
    @ConfigString(key = SERVICE_SDK_URL)
    String getServiceSdkUrl();

    /**
     * The datacenter ID. The datacenter will be used as base object to check
     * permissions on
     */
    @ConfigString(key = SERVICE_DC_ID)
    String getServiceDCId();

    /**
     * If "true" the server certificate validation will be turned off.
     * This means that every server certificate will considered VALID.
     * Please DO NOT ENABLE this in production, it's not a good idea
     */
    @ConfigString(key = IGNORE_SERVER_CERT, defaultValue = "false")
    String ignoreServerCert();

    /**
     *  the server certificate fingerprint in the form of an hex SHA-1
     *  hash, eg: "20:4D:FB:4E:07:D8:E3:7F:67:AD:93:1A:8A:64:65:49:12:E8:50:88"
     */
    @ConfigString(key = SERVICE_SSL_CERT_SHA1_FINGERPRINT, defaultValue = "")
    String getServiceSSLCertFingerprint();

    /**
    * The ADMIN authentication token. Useful for script authentication.
    * This token will authenticate to the API with the ADMIN role
    */
    @ConfigString(key = ADMIN_TOKEN)
    String getAdminToken();
}