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
package org.midonet.api.auth;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.midonet.api.rest_api.ResponseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet filter for authentication.
 */
@Singleton
public final class AuthFilter implements Filter {

    private final static Logger log = LoggerFactory.getLogger(AuthFilter.class);

    /**
     * User identity key
     */
    public static final String USER_IDENTITY_ATTR_KEY =
            UserIdentity.class.getName();

    /**
     * HTTP extension token header
     */
    public final static String HEADER_X_AUTH_TOKEN = "X-Auth-Token";

    @Inject
    private AuthService service;

    /**
     * Called by the web container to indicate to a filter that it is being
     * placed into service.
     *
     * @param filterConfig
     *            A filter configuration object used by a servlet container to
     *            pass information to a filter during initialization.
     * @throws ServletException
     *             A servlet error.
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.debug("AuthFilter.init: entered.");
    }

    /**
     * Called by the container each time a request/response pair is passed
     * through the chain due to a client request for a resource at the end of
     * the chain.
     *
     * @param request
     *            Request passed along the chain.
     * @param response
     *            Response passed along the chain.
     * @param chain
     *            Filter chain to keep the request going.
     * @throws IOException
     *             Auth client IO error.
     * @throws ServletException
     *             A servlet error.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        log.debug("AuthFilter: entered doFilter.");
        HttpServletRequest req = (HttpServletRequest) request; // Assume HTTP.

        String token = req.getHeader(HEADER_X_AUTH_TOKEN);

        UserIdentity user = null;
        try {
            // It will accept null token since client implementations may
            // want to treat such case differently.
            user = this.service.getUserIdentityByToken(token);
        } catch (AuthException ex) {
            ResponseUtils.setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
            log.error("AuthFilter: auth error occurred. ", ex);
            return;
        }

        if (user != null) {
            req.setAttribute(USER_IDENTITY_ATTR_KEY, user);
            chain.doFilter(request, response);
        } else {
            // This is the case where a token was invalid.  Challenge the
            // client to submit Basic auth credentials.
            ResponseUtils.setAuthErrorResponse((HttpServletResponse) response,
                    "Authentication error");
        }

        log.debug("AuthFilter: exiting doFilter.");
    }

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     */
    @Override
    public void destroy() {
        log.debug("AuthFilter.destroy: entered.");
        // Reset all the member variables.
        this.service = null;
    }
}
