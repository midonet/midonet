/*
 * Copyright 2012 Midokura KK
 */

package com.midokura.midolman.mgmt.servlet;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet filter which allows clients cross-origin request sharing (CORS).
 *
 * @author Taku Fukushima <tfukushima@midokura.com>
 */
public final class CrossOriginResourceSharingFilter implements Filter {
    private final static String HTTP_METHOD_OPTIONS = "OPTIONS";
    private final static String ACCESS_CONTROL_ALLOW_ORIGIN_KEY =
            "Access-Control-Allow-Origin";
    private final static String ACCESS_CONTROL_ALLOW_CREDENTIALS_KEY =
            "Access-Control-Allow-Credentials";
    private final static String ACCESS_CONTROL_ALLOW_HEADERS_KEY =
            "Access-Control-Allow-Headers";
    private final static String ACCESS_CONTROL_ALLOW_METHODS_KEY =
            "Access-Control-Allow-Methods";
    private final static String ACCESS_CONTROL_EXPOSE_HEADERS_KEY =
            "Access-Control-Expose-Headers";
    private static String ACCESS_CONTROL_ALLOW_ORIGIN_VALUE;
    private static String ACCESS_CONTROL_ALLOW_CREDENTIALS_VALUE;
    private static String ACCESS_CONTROL_ALLOW_HEADERS_VALUE;
    private static String ACCESS_CONTROL_ALLOW_METHODS_VALUE;
    private static String ACCESS_CONTROL_EXPOSE_HEADERS_VALUE;
    private final static Logger log =
            LoggerFactory.getLogger(CrossOriginResourceSharingFilter.class);

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
        log.debug("Initializing CrossOriginResourceSharingFilter.");

        // Get parameters from web.xml.
        ACCESS_CONTROL_ALLOW_ORIGIN_VALUE = filterConfig.getInitParameter(
                ACCESS_CONTROL_ALLOW_ORIGIN_KEY);
        ACCESS_CONTROL_ALLOW_CREDENTIALS_VALUE = filterConfig.getInitParameter(
                ACCESS_CONTROL_ALLOW_CREDENTIALS_KEY);
        ACCESS_CONTROL_ALLOW_HEADERS_VALUE = filterConfig.getInitParameter(
                ACCESS_CONTROL_ALLOW_HEADERS_KEY);
        ACCESS_CONTROL_ALLOW_METHODS_VALUE = filterConfig.getInitParameter(
                ACCESS_CONTROL_ALLOW_METHODS_KEY);
        ACCESS_CONTROL_EXPOSE_HEADERS_VALUE = filterConfig.getInitParameter(
                ACCESS_CONTROL_EXPOSE_HEADERS_KEY);
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
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // This should be added in response to both the preflight and the
        // actual request
        response.addHeader(ACCESS_CONTROL_ALLOW_ORIGIN_KEY,
                ACCESS_CONTROL_ALLOW_ORIGIN_VALUE);
        response.addHeader(ACCESS_CONTROL_ALLOW_METHODS_KEY,
                ACCESS_CONTROL_ALLOW_METHODS_VALUE);
        response.addHeader(ACCESS_CONTROL_EXPOSE_HEADERS_KEY,
                ACCESS_CONTROL_EXPOSE_HEADERS_VALUE);
        if (HTTP_METHOD_OPTIONS.equalsIgnoreCase(request.getMethod())) {
            response.addHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS_KEY,
                    ACCESS_CONTROL_ALLOW_CREDENTIALS_VALUE);
            response.addHeader(ACCESS_CONTROL_ALLOW_HEADERS_KEY,
                    ACCESS_CONTROL_ALLOW_HEADERS_VALUE);
        }
        filterChain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        log.debug("Destroying CrossOriginResourceSharingFilter resources");
    }
}
