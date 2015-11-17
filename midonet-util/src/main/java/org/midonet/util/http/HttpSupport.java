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
package org.midonet.util.http;

/**
 * Contains utilities for HTTP
 */
public class HttpSupport {

    public final static String UTF8_ENC = "UTF-8";

    public final static String OPTIONS_METHOD = "OPTIONS";

    public final static String SET_COOKIE = "Set-Cookie";
    public final static String SET_COOKIE_SESSION_KEY = "sessionId";
    public final static String SET_COOKIE_EXPIRES = "Expires";
    public final static String SET_COOKIE_EXPIRES_FORMAT =
            "EEE, dd-MM-yyyy HH:mm:ss z";
    public final static String WWW_AUTHENTICATE = "WWW-Authenticate";
    public final static String BASIC_AUTH = "Basic";
    public final static String BASIC_AUTH_PREFIX = BASIC_AUTH + " ";
    public final static String BASIC_AUTH_REALM_FIELD =
            BASIC_AUTH_PREFIX + " realm=\"BASIC_AUTH_REALM\"";

    public final static String ACCESS_CONTROL_ALLOW_ORIGIN_KEY =
            "Access-Control-Allow-Origin";
    public final static String ACCESS_CONTROL_ALLOW_CREDENTIALS_KEY =
            "Access-Control-Allow-Credentials";
    public final static String ACCESS_CONTROL_ALLOW_HEADERS_KEY =
            "Access-Control-Allow-Headers";
    public final static String ACCESS_CONTROL_ALLOW_METHODS_KEY =
            "Access-Control-Allow-Methods";
    public final static String ACCESS_CONTROL_EXPOSE_HEADERS_KEY =
            "Access-Control-Expose-Headers";
}
