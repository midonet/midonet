/*
 * @(#)KeystoneAuthFilter.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.servlet.filters;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.midokura.midolman.mgmt.auth.KeystoneClient;

/**
 * Servlet filter for Keystone authentication.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public final class KeystoneAuthFilter implements Filter {
    /*
     * Servlet filter that authenticates the passed-in token via
     * Keystone authentication server.
     */

    private KeystoneClient client = null;

    /**
     * Called by the web container to indicate to a filter that it is being
     * placed into service.
     *
     * @param   filterConfig  A filter configuration object used by a servlet
     *                        servlet container to pass information to a
     *                        filter during initialization.
     * @throws  ServletException  A servlet error.
     */
    public void init(FilterConfig filterConfig) throws ServletException {
        // Initialize member variables. 
        String protocol = filterConfig.getInitParameter("service_protocol");
        String host = filterConfig.getInitParameter("service_host");
        int port = Integer.parseInt(filterConfig.getInitParameter(
                "service_port"));
        this.client = new KeystoneClient(protocol, host, port);
        this.client.setAdminToken(filterConfig.getInitParameter(
                "admin_token"));
    }

    /**
     * Called by the container each time a request/response pair is passed
     * through the chain due to a client request for a resource at the end of
     * the chain.
     *
     * @param   request  Request passed along the chain.
     * @param   response  Response passed along the chain.
     * @param   chain  Filter chain to keep the request going.
     * @throws  IOException  Keystone client IO error.
     * @throws  ServletException  A servlet error. 
     */
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {
        // Ask the Keystone server if the token is valid. 
        HttpServletRequest req = (HttpServletRequest) request; // Assume HTTP.
        String token = req.getHeader("HTTP_X_AUTH_TOKEN"); // Get token.
        if (this.client.validateToken(token)) {
            chain.doFilter(request, response); // Keep the chain going.
        } else {
        	HttpServletResponse resp = (HttpServletResponse) response;
        	resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    /**
    * Called by the web container to indicate to a filter that it is being
    * taken out of service.
    */
    public void destroy() {
        // Reset all the member variables. 
        this.client = null;
    }
}
