/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.ResponseUtils;
import org.midonet.cluster.data.SystemState;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet filter for API server state
 */
@Singleton
public final class StateFilter implements Filter {

    private final static Logger log = LoggerFactory.getLogger(StateFilter.class);

    @Inject
    private DataClient dataClient;

    @Inject
    private PathBuilder paths;

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
        log.debug("StateFilter: entered doFilter.");
        HttpServletRequest req = (HttpServletRequest) request;
        String[] allowedMediaTypes = {
                VendorMediaType.APPLICATION_HOST_VERSION_JSON,
                VendorMediaType.APPLICATION_WRITE_VERSION_JSON,
                VendorMediaType.APPLICATION_SYSTEM_STATE_JSON};

        String availability;
        try {
            SystemState systemState = dataClient.systemStateGet();
            availability = systemState.getAvailability();
        } catch(StateAccessException ex) {
            ResponseUtils.setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Can not access topology information");
            log.error("Can not access topology information");
            return;
        }
        String contentType = req.getContentType();
        if (availability.equals(
                SystemState.Availability.READONLY.toString()) &&
                !req.getMethod().equals("GET") &&
                (contentType != null)) {
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

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     */
    @Override
    public void destroy() {
        log.debug("StateFilter.destroy: entered.");
    }
}
