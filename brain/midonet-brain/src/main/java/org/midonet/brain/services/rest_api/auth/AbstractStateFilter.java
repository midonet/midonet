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
package org.midonet.brain.services.rest_api.auth;

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
import org.slf4j.LoggerFactory;

import org.midonet.brain.services.rest_api.VendorMediaType;
import org.midonet.brain.services.rest_api.rest_api.ResponseUtils;
import org.midonet.cluster.backend.zookeeper.StateAccessException;

@Singleton
public abstract class AbstractStateFilter implements Filter {

    private final static Logger log = LoggerFactory.getLogger(AbstractStateFilter.class);

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
        log.debug("StateFilter.init: entered.");
    }

    /**
     * Called by the container each time a request/response pair is passed
     * through the chain due to a client request for a resource at the end of
     * the chain.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        log.debug("StateFilter: entered doFilter.");
        HttpServletRequest req = (HttpServletRequest) request;
        String[] allowedMediaTypes = {
                VendorMediaType.APPLICATION_HOST_VERSION_JSON,
                VendorMediaType.APPLICATION_WRITE_VERSION_JSON,
                VendorMediaType.APPLICATION_SYSTEM_STATE_JSON,
                VendorMediaType.APPLICATION_SYSTEM_STATE_JSON_V2};

        boolean isReadonly;
        try {
            isReadonly = isStateReadonly();
        } catch(StateAccessException ex) {
            ResponseUtils.setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Can not access topology information");
            log.error("Can not access topology information");
            return;
        }
        String contentType = req.getContentType();
        if (isReadonly &&
            !req.getMethod().equals("GET") && (contentType != null)) {
            // Allow all GET operations in limited mode, but also allow writes
            // to the admin level data. If the content-type is null, we can not
            // filter based on this.
            for (String mediaType : allowedMediaTypes) {
                if (contentType.equals(mediaType)) {
                    chain.doFilter(request, response);
                    log.debug("StateFilter: exiting doFilter.");
                    return;
                }
            }
            ResponseUtils.setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "API server currently in restricted mode.");
            log.error("API server currently in restricted mode.");
            return;
        }

        chain.doFilter(request, response);
        log.debug("StateFilter: exiting doFilter.");
    }

    public abstract boolean isStateReadonly() throws StateAccessException;

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     */
    @Override
    public void destroy() {
        log.debug("StateFilter.destroy: entered.");
    }
}
