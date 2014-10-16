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

package org.midonet.api.auth.cors;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.midonet.api.HttpSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet filter which allows clients cross-origin request sharing (CORS).
 *
 * @author Taku Fukushima <tfukushima@midokura.com>
 */
@Singleton
public final class CrossOriginResourceSharingFilter implements Filter {

    private final static Logger log =
            LoggerFactory.getLogger(CrossOriginResourceSharingFilter.class);

    @Inject
    private CorsConfig config;

    /**
     * Called by the web container to indicate to a filter that it is being
     * placed into service.
     *
     * @param filterConfig
     *         A filter configuration object used by a servlet container to
     *         pass information to a filter during initialization.
     * @throws ServletException
     *         A servlet error.
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.debug("CrossOriginResourceSharingFilter.init: entered.");
    }

    /**
     * Called by the container each time a request/response pair is passed
     * through the chain due to a client request for a resource at the end
     * of the chain. This adds "Access-Control-Allow-Origin" to the headers
     * to allow clients CORS. Refer the following pages to get more details.
     *
     * @see <a href="http://en.wikipedia.org/wiki/Same_origin_policy">
     *     Same origin policy - Wikipedia, the free encyclopedia
     *     </a>
     * @see <a href="http://www.w3.org/TR/cors/#cross-origin-request-with-
     *               preflight0">
     *     Cross-Origin Resource Sharing
      *    </a>
     * @see <a href="http://stackoverflow.com/questions/5406350/access-control-
     *               allow-origin-has-no-influence-in-rest-web-service">
     *     “Access-Control-Allow-Origin:*” has no influence in REST Web Service
     *     </a>
     *
     * @param servletRequest
     *         Request passed along the chain.
     * @param servletResponse
     *         Response passed along the chain.
     * @param filterChain
     *         Filter chain to keep the request going.
     * @throws IOException
     *         IO error.
     * @throws ServletException
     *         A servlet error.
     */
    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse,
                         FilterChain filterChain)
            throws IOException, ServletException {
        log.debug("CrossOriginResourceSharingFilter.doFilter: entered.");

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // This should be added in response to both the preflight and the
        // actual request
        response.addHeader(HttpSupport.ACCESS_CONTROL_ALLOW_ORIGIN_KEY,
                config.getAccessControlAllowOrigin());
        response.addHeader(HttpSupport.ACCESS_CONTROL_ALLOW_METHODS_KEY,
                config.getAccessControlAllowMethods());
        response.addHeader(HttpSupport.ACCESS_CONTROL_EXPOSE_HEADERS_KEY,
                config.getAccessControlExposeHeaders());

        if (HttpSupport.OPTIONS_METHOD.equalsIgnoreCase(
                request.getMethod())) {

            // TODO: Credentials are ignored.  Handle credentials when the
            // allow origin header is not wild-carded.
            response.addHeader(HttpSupport.ACCESS_CONTROL_ALLOW_CREDENTIALS_KEY,
                    null);
            response.addHeader(HttpSupport.ACCESS_CONTROL_ALLOW_HEADERS_KEY,
                    config.getAccessControlAllowHeaders());
            log.debug("CrossOriginResourceSharingFilter.doFilter: exiting " +
                    "after handling OPTION.");
            return;
        }
        filterChain.doFilter(request, response);

        log.debug("CrossOriginResourceSharingFilter.doFilter: exiting.");
    }

    @Override
    public void destroy() {
        log.debug("CrossOriginResourceSharingFilter.destroy: entered.");
    }
}
