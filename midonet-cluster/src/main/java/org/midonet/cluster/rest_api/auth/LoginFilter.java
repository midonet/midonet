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
package org.midonet.cluster.rest_api.auth;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.jasypt.contrib.org.apache.commons.codec_1_3.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthException;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.Token;
import org.midonet.cluster.package$;
import org.midonet.cluster.rest_api.ResponseUtils;
import org.midonet.util.http.HttpSupport;

/**
 * Servlet Filter to authenticate a user with username and password
 */
@Singleton
public class LoginFilter implements Filter {

    private final static Logger log = LoggerFactory
        .getLogger(package$.MODULE$.authLog());

    protected ServletContext servletContext;

    @Inject
    private AuthService service;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        servletContext = filterConfig.getServletContext();
    }

    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse,
                         FilterChain filterChain)
            throws IOException, ServletException {
        log.debug("Processing login request");

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // Get the Authorization header. 'getHeader' is case insensitive
        String authorization = request.getHeader("authorization");
        if (StringUtils.isEmpty(authorization)) {
            ResponseUtils.setAuthErrorResponse(response,
                                           "Authorization header is not set.");
            return;
        }

        // Support only Basic
        if (!authorization.toLowerCase().startsWith(
                HttpSupport.BASIC_AUTH_PREFIX.toLowerCase())) {
            ResponseUtils.setAuthErrorResponse(response,
                    "Authorization header does not contain Basic.");
            return;
        }

        // Get the base64 portion
        String credentialsEnc = authorization.substring(
                HttpSupport.BASIC_AUTH_PREFIX.length());

        // Decode base64
        String credentials = new String(Base64.decodeBase64(credentialsEnc
                                                                .getBytes()));

        // Get the username/password
        String[] credList = credentials.split(":");
        if (credList.length != 2) {
            ResponseUtils.setAuthErrorResponse(response,
                    "Authorization header is not valid");
            return;
        }

        try {
            Token token = service.login(credList[0], credList[1], request);
            // Set the Cookie
            ResponseUtils.setCookie(response, token.getKey(),
                                    token.getExpiresString());
            // Set the Token object as the body of the response.
            ResponseUtils.setEntity(response, token);
        } catch (AuthException ex) {
            ResponseUtils.setAuthErrorResponse(response, ex.getMessage());
            log.error("Login authorization error occurred for user {}",
                      request.getRemoteUser(), ex);
        }
    }

    @Override
    public void destroy() {
    }
}
