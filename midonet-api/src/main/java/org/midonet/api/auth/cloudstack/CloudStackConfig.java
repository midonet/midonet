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
