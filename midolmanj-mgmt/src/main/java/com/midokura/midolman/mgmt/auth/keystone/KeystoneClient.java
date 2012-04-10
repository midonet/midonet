/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.keystone;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.FilterConfig;
import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthClient;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.UserIdentity;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
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

    private final String protocol;
    private final String host;
    private final int port;
    private final String adminRole;
    private final String tenantAdminRole;
    private final String tenantUserRole;
    private final String adminToken;
    private final String serviceUrl;

    public static final String KEYSTONE_TOKEN_HEADER_KEY = "X-Auth-Token";
    public static final String KEYSTONE_PROTOCOL_CONFIG_KEY = "keystone_service_protocol";
    public static final String KEYSTONE_HOST_CONFIG_KEY = "keystone_service_host";
    public static final String KEYSTONE_PORT_CONFIG_KEY = "keystone_service_port";
    public static final String KEYSTONE_ADMIN_ROLE_CONFIG_KEY = "keystone_admin_role";
    public static final String KEYSTONE_TENANT_ADMIN_ROLE_CONFIG_KEY = "keystone_tenant_admin_role";
    public static final String KEYSTONE_TENANT_USER_ROLE_CONFIG_KEY = "keystone_tenant_user_role";
    public static final String KEYSTONE_ADMIN_TOKEN_KEY = "keystone_admin_token";

    /**
     * Create a KeystoneClient object from a FilterConfig object.
     *
     * @param filterConfig
     *            FilterConfig object.
     */
    public KeystoneClient(FilterConfig config) {
        log.debug("KeystoneClient: entered constructor.");

        try {
            this.protocol = config
                    .getInitParameter(KEYSTONE_PROTOCOL_CONFIG_KEY);
            this.host = config.getInitParameter(KEYSTONE_HOST_CONFIG_KEY);
            this.port = Integer.parseInt(config
                    .getInitParameter(KEYSTONE_PORT_CONFIG_KEY));
            this.adminRole = config.getInitParameter(
                    KEYSTONE_ADMIN_ROLE_CONFIG_KEY).toLowerCase();
            this.tenantAdminRole = config.getInitParameter(
                    KEYSTONE_TENANT_ADMIN_ROLE_CONFIG_KEY).toLowerCase();
            this.tenantUserRole = config.getInitParameter(
                    KEYSTONE_TENANT_USER_ROLE_CONFIG_KEY).toLowerCase();
            this.adminToken = config.getInitParameter(KEYSTONE_ADMIN_TOKEN_KEY);
        } catch (Exception ex) {
            throw new InvalidConfigException(
                    "Cannot instantiate KeystoneClient from the config ", ex);
        }

        this.serviceUrl = new StringBuilder(protocol).append("://")
                .append(host).append(":").append(Integer.toString(port))
                .append("/v2.0").toString();

        log.debug("KeystoneClient: exiting constructor.  url={}",
                this.serviceUrl);
    }

    private String convertToAuthRole(String role) {
        String roleLowerCase = role.toLowerCase();
        if (roleLowerCase.equals(this.adminRole)) {
            return AuthRole.ADMIN;
        } else if (roleLowerCase.equals(this.tenantAdminRole)) {
            return AuthRole.TENANT_ADMIN;
        } else if (roleLowerCase.equals(this.tenantUserRole)) {
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
        return protocol;
    }

    /**
     * @return the host
     */
    public String getHost() {
        return host;
    }

    /**
     * @return the port
     */
    public int getPort() {
        return port;
    }

    /**
     * @return the adminRole
     */
    public String getAdminRole() {
        return adminRole;
    }

    /**
     * @return the tenantAdminRole
     */
    public String getTenantAdminRole() {
        return tenantAdminRole;
    }

    /**
     * @return the tenantUserRole
     */
    public String getTenantUserRole() {
        return tenantUserRole;
    }

    /**
     * @return the serviceUrl
     */
    public String getServiceUrl() {
        return serviceUrl;
    }

    /**
     * @return the adminToken
     */
    public String getAdminToken() {
        return adminToken;
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
        } catch (JsonParseException e) {
            log.debug("KeystoneClient: Could not create JsonParser.");
            throw new KeystoneInvalidJsonException(e);
        } catch (IOException e) {
            log.debug("KeystoneClient: IO error while creating JsonParser.");
            throw new KeystoneInvalidJsonException(e);
        }

        JsonNode rootNode = null;
        try {
            rootNode = mapper.readTree(jp);
        } catch (JsonProcessingException e) {
            log.debug("KeystoneClient: Could not parse JSON.");
            throw new KeystoneInvalidJsonException(e);
        } catch (IOException e) {
            log.debug("KeystoneClient: IO error while parsing JSON.");
            throw new KeystoneInvalidJsonException(e);
        }

        UserIdentity user = new UserIdentity();
        JsonNode node = rootNode.get("access");
        user.setUserId(node.get("user").get("username").getTextValue());
        String tenantId = node.get("token").get("tenant").get("id")
                .getTextValue();
        user.setTenantId(tenantId);
        user.setTenantName(node.get("token").get("tenant").get("name")
                .getTextValue());
        user.setToken(node.get("token").get("id").getTextValue());

        JsonNode roleNode = node.get("user").get("roles");
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

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.auth.AuthClient#getUserIdentityByToken(java
     * .lang.String)
     */
    @Override
    public UserIdentity getUserIdentityByToken(String token) {
        log.debug("KeystoneClient: entered getUserIdentityByToken.  Token={}",
                token);
        if (token == null) {
            throw new IllegalArgumentException(token);
        }

        String url = new StringBuilder(this.serviceUrl).append("/tokens/")
                .append(token).toString();
        Client client = Client.create();
        WebResource resource = client.resource(url);
        String response = null;
        try {
            response = resource.accept(MediaType.APPLICATION_JSON)
                    .header(KEYSTONE_TOKEN_HEADER_KEY, this.adminToken)
                    .get(String.class);
        } catch (UniformInterfaceException e) {
            log.warn("KeystoneClient: Invalid request. admin="
                    + this.adminToken + ", token=" + token);
            return null;
        } catch (ClientHandlerException e) {
            log.warn("KeystoneClient: Could not connect. serviceUrl="
                    + this.serviceUrl + ", token=" + token);
            return null;
        }

        try {
            return parseJson(response);
        } catch (KeystoneInvalidJsonException e) {
            log.error("KeystoneClient: could not parse JSON", e);
            return null;
        }
    }
}
