/*
 * @(#)KeystoneAuthFilter.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.KeystoneClient;
import com.midokura.midolman.mgmt.auth.TenantUser;

/**
 * Servlet filter for Keystone authentication.
 *
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public final class KeystoneAuthFilter implements Filter {
    /*
     * Servlet filter that authenticates the passed-in token via Keystone
     * authentication server.
     */

    private KeystoneClient client = null;
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);
    private final static Logger log = LoggerFactory
            .getLogger(KeystoneAuthFilter.class);

    public static class AuthError {
        private int code;
        private String message;

        public AuthError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    private String generateJsonError(int code, String msg) throws IOException {
        AuthError err = new AuthError(code, msg);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(bos,
                JsonEncoding.UTF8);
        jsonGenerator.writeObject(err);
        bos.close();
        return bos.toString("UTF-8");
    }

    private void setErrorResponse(HttpServletResponse resp, int code, String msg)
            throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(code);
        resp.getWriter().write(generateJsonError(code, msg));
    }

    /**
     * Called by the web container to indicate to a filter that it is being
     * placed into service.
     *
     * @param filterConfig
     *            A filter configuration object used by a servlet servlet
     *            container to pass information to a filter during
     *            initialization.
     * @throws ServletException
     *             A servlet error.
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Initialize member variables.
        log.debug("Initializing KeystoneAuthFilter.");
        String protocol = filterConfig.getInitParameter("service_protocol");
        String host = filterConfig.getInitParameter("service_host");
        int port = Integer.parseInt(filterConfig
                .getInitParameter("service_port"));
        this.client = new KeystoneClient(protocol, host, port);
        this.client.setAdminToken(filterConfig.getInitParameter("admin_token"));
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
     *             Keystone client IO error.
     * @throws ServletException
     *             A servlet error.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        // Ask the Keystone server if the token is valid.
        log.debug("Filtering request for Keystone authentication.");
        HttpServletRequest req = (HttpServletRequest) request; // Assume HTTP.
        String token = req.getHeader("HTTP_X_AUTH_TOKEN"); // Get token.
        if (token == null) {
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "No token was passed in.");
            return;
        }
        TenantUser tu = null;
        try {
            tu = this.client.getTenantUser(token);
        } catch (Exception ex) {
            // Unknown error occurred.
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Unknown error occurred while validating token.");
            return;
        }

        if (tu != null) {
            req.setAttribute("com.midokura.midolman.mgmt.auth.TenantUser", tu);
            chain.doFilter(request, response); // Keep the chain going.
        } else {
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid token passed in.");
        }
    }

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     */
    @Override
    public void destroy() {
        log.debug("Destroying KeystoneAuthFilter resources.");
        // Reset all the member variables.
        this.client = null;
    }
}
