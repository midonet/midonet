/*
 * Copyright 2015 Midokura SARL
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
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.inject.Singleton;

import org.slf4j.Logger;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.auth.UserIdentity;
import org.midonet.cluster.package$;
import org.midonet.cluster.rest_api.ResponseUtils;

import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.midonet.cluster.rest_api.auth.AuthFilter.USER_IDENTITY_ATTR_KEY;
import static org.slf4j.LoggerFactory.getLogger;

/** This filter restricts the API only to the admin tenant. */
@Singleton
public final class AdminOnlyAuthFilter implements Filter {

    private final static Logger log = getLogger(package$.MODULE$.authLog());

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    /**
     * Called by the container each time a request/response pair is passed
     * through the chain due to a client request for a resource at the end of
     * the chain.
     *
     * @param request Request passed along the chain.
     * @param response Response passed along the chain.
     * @param chain Filter chain to keep the request going.
     * @throws IOException Auth client IO error.
     * @throws ServletException A servlet error.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request; // Assume HTTP.
        UserIdentity uid = (UserIdentity)req.getAttribute(USER_IDENTITY_ATTR_KEY);
        if (uid.hasRole(AuthRole.ADMIN)) {
            log.info("User authenticated as admin: " + uid.userId);
            chain.doFilter(request, response);
        } else {
            log.info("User was authenticated, but not an admin: " + uid.userId);
            ResponseUtils.setErrorResponse((HttpServletResponse) response,
                                           SC_UNAUTHORIZED, null);
        }
    }

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     */
    @Override
    public void destroy() {
    }
}
