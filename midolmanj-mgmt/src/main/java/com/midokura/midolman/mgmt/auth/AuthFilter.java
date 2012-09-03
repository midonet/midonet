/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.error.ErrorEntity;
import com.midokura.midolman.mgmt.HttpSupport;
import com.midokura.midolman.mgmt.VendorMediaType;

/**
 * Servlet filter for authentication.
 */
@Singleton
public final class AuthFilter implements Filter {

    private final static Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    /**
     * User identity key
     */
    public static final String USER_IDENTITY_ATTR_KEY =
            UserIdentity.class.getName();

    @Inject
    private AuthClient client;

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

    private void setErrorResponse(HttpServletResponse resp, int code,
                                  String msg) throws IOException {
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

        String token = req.getHeader(HttpSupport.TOKEN_HEADER);
        if (token == null) {
            // For legacy support
            token = req.getHeader("HTTP_X_AUTH_TOKEN");
        }

        UserIdentity user = null;
        try {
            // It will accept null token since client implementations may
            // want to treat such case differently.
            user = this.client.getUserIdentityByToken(token);
        } catch (AuthException ex) {
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
            log.error("AuthFilter: auth error occurred. ", ex);
            return;
        }

        if (user != null) {
            req.setAttribute(USER_IDENTITY_ATTR_KEY, user);
            chain.doFilter(request, response);
        } else {
            setErrorResponse((HttpServletResponse) response,
                    HttpServletResponse.SC_UNAUTHORIZED,
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
        this.client = null;
    }
}
