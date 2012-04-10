/*
 * Copyright 2012 Midokura PTE LTD.
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

import com.midokura.midolman.mgmt.auth.AuthClient;
import com.midokura.midolman.mgmt.auth.AuthClientFactory;
import com.midokura.midolman.mgmt.auth.UserIdentity;
import com.midokura.midolman.mgmt.data.dto.ErrorEntity;
import com.midokura.midolman.mgmt.rest_api.core.HttpSupport;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;

/**
 * Servlet filter for authentication.
 */
public final class AuthFilter implements Filter {

    private final static Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private AuthClient client = null;

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
        log.debug("AuthFilter: Initializing.");
        this.client = AuthClientFactory.create(filterConfig);
        log.debug("AuthFilter: Initialized.");
    }

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    private String generateJsonError(int code, String msg) throws IOException {
        ErrorEntity err = new ErrorEntity();
        err.setCode(code);
        err.setMessage(msg);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(bos,
                JsonEncoding.UTF8);
        jsonGenerator.writeObject(err);
        bos.close();
        return bos.toString(HttpSupport.UTF8_ENC);
    }

    private void setErrorResponse(HttpServletResponse resp, int code, String msg)
            throws IOException {
        resp.setContentType(VendorMediaType.APPLICATION_ERROR_JSON);
        resp.setCharacterEncoding(HttpSupport.UTF8_ENC);
        resp.setStatus(code);
        resp.getWriter().write(generateJsonError(code, msg));
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

        String method = req.getMethod();

        // Don't check X-Auth-Token if the method is OPTIONS because
        // preflight request is automatically done by the browser and there
        // is no way that the client code can set the token for the request.
        if (method.equals(HttpSupport.OPTIONS_METHOD)) {
            return;
        }

        String token = req.getHeader(HttpSupport.TOKEN_HEADER);
        if (token == null) {
            // For legacy support
            token = req.getHeader("HTTP_X_AUTH_TOKEN");
        }
        if (token == null) {
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "No token was passed in.");
            log.error("AuthFilter: No token was passed in.");
            return;
        }

        UserIdentity user = this.client.getUserIdentityByToken(token);
        if (user != null) {
            req.setAttribute(ServletSupport.USER_IDENTITY_ATTR_KEY, user);
            chain.doFilter(request, response);
        } else {
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication error");
            log.error("AuthFilter: Got null with token {}", token);
        }

        log.debug("AuthFilter: exiting doFilter.");
    }

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     */
    @Override
    public void destroy() {
        log.debug("AuthFilter: entered destroy.");
        // Reset all the member variables.
        this.client = null;
        log.debug("AuthFilter: exiting destroy.");
    }
}
