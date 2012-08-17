/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.keystone;

import java.io.IOException;
import java.util.Iterator;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.inject.Inject;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthClient;
import com.midokura.midolman.mgmt.auth.AuthException;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.InvalidTokenException;
import com.midokura.midolman.mgmt.auth.UserIdentity;
import com.midokura.util.StringUtil;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;

/**
 * Keystone Client.
 */
public class KeystoneClient implements AuthClient {

    private final static Logger log = LoggerFactory
            .getLogger(KeystoneClient.class);

    private final KeystoneConfig config;

    public static final String KEYSTONE_TOKEN_HEADER_KEY = "X-Auth-Token";

    /**
     * Create a KeystoneClient object from a KeystoneConfig object.
     *
     * @param config
     *            KeystoneConfig object.
     */
    @Inject
    public KeystoneClient(KeystoneConfig config) {
        this.config = config;
    }

    private String convertToAuthRole(String role) {
        String roleLowerCase = role.toLowerCase();
        if (roleLowerCase.equals(getAdminRole())) {
            return AuthRole.ADMIN;
        } else if (roleLowerCase.equals(getTenantAdminRole())) {
            return AuthRole.TENANT_ADMIN;
        } else if (roleLowerCase.equals(getTenantUserRole())) {
            return AuthRole.TENANT_USER;
        } else {
            // Unknown roles are ignored.
            return null;
        }
    }

    /**
     * @return the protocol
     */
    public String getProtocol() {
        return config.getServiceProtocol();
    }

    /**
     * @return the host
     */
    public String getHost() {
        return config.getServiceHost();
    }

    /**
     * @return the port
     */
    public int getPort() {
        return config.getServicePort();
    }

    /**
     * @return the adminRole
     */
    public String getAdminRole() {
        return config.getAdminRole();
    }

    /**
     * @return the tenantAdminRole
     */
    public String getTenantAdminRole() {
        return config.getTenantAdminRole();
    }

    /**
     * @return the tenantUserRole
     */
    public String getTenantUserRole() {
        return config.getTenantUserRole();
    }

    /**
     * @return the serviceUrl
     */
    public String getServiceUrl() {
        return new StringBuilder(getProtocol()).append("://")
                .append(getHost()).append(":").append(
                        Integer.toString(getPort()))
                .append("/v2.0").toString();
    }

    /**
     * @return the adminToken
     */
    public String getAdminToken() {
        return config.getAdminToken();
    }

    /**
     * Parse a JSON string to UserIdentity object.
     *
     * @param src
     *            String to parse.
     * @return UserIdentity object.
     * @throws KeystoneInvalidJsonException
     */
    public UserIdentity parseJson(String src)
            throws KeystoneInvalidJsonException {
        log.debug("KeystoneClient: entered entered {}", src);

        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getJsonFactory();
        JsonParser jp = null;
        try {
            jp = factory.createJsonParser(src);
        } catch (IOException e) {
            throw new KeystoneInvalidJsonException(
                    "Could not parse Keystone response.", e);
        }

        JsonNode rootNode = null;
        try {
            rootNode = mapper.readTree(jp);
        } catch (IOException e) {
            throw new KeystoneInvalidJsonException(
                    "Could not parse Keystone response.", e);
        }

        UserIdentity user = new UserIdentity();

        // Get the token info
        JsonNode tokenNode = rootNode.get("access").get("token");
        user.setToken(tokenNode.get("id").getTextValue());
        JsonNode tenantNode = tokenNode.get("tenant");
        if (tenantNode == null) {
            throw new KeystoneInvalidJsonException(
                    "Tenant information is missing from this token.");
        }
        user.setTenantId(tenantNode.get("id").getTextValue());
        user.setTenantName(tenantNode.get("name").getTextValue());

        // Get the user info
        JsonNode userNode = rootNode.get("access").get("user");
        user.setUserId(userNode.get("username").getTextValue());
        JsonNode roleNode = userNode.get("roles");
        Iterator<JsonNode> roleNodeItr = roleNode.getElements();
        String ksRole, authRole = null;
        while (roleNodeItr.hasNext()) {
            roleNode = roleNodeItr.next();
            ksRole = roleNode.get("name").getTextValue();
            authRole = convertToAuthRole(ksRole);
            if (authRole != null) {
                user.addRole(authRole);
            }
        }

        log.debug("KeystoneClient: existing parse {}", user);
        return user;
    }

    @Override
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException {
        log.debug("KeystoneClient: entered getUserIdentityByToken.  Token={}",
                token);

        if (StringUtil.isNullOrEmpty(token)) {
            // Don't allow empty token
            throw new InvalidTokenException("No token was passed in.");
        }

        String url = new StringBuilder(getServiceUrl()).append("/tokens/")
                .append(token).toString();
        Client client = Client.create();
        WebResource resource = client.resource(url);
        String response = null;
        try {
            response = resource.accept(MediaType.APPLICATION_JSON)
                    .header(KEYSTONE_TOKEN_HEADER_KEY, getAdminToken())
                    .get(String.class);
        } catch (UniformInterfaceException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND
                    .getStatusCode()) {
                // This indicates that the token was invalid
                log.warn("KeystoneClient: Invalid token. {}", token);
                return null;
            }
            throw new KeystoneServerException("Keystone server error.", e);
        } catch (ClientHandlerException e) {
            throw new KeystoneConnectionException(
                    "Could not connect to Keystone server. Url=" + url, e);
        }

        return parseJson(response);
    }
}
