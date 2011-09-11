/*
 * @(#)KeystoneJsonParser        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import java.io.IOException;
import java.util.Iterator;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * JSON parser for Keystone response.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public final class KeystoneJsonParser {
    /*
     * Wrapper for Jackson JSON Parser to parse the response from Keystone.
     */

    private JsonNode node = null;

    /**
     * Default constructor.
     */
    public KeystoneJsonParser() {
    }

    /**
     * Parse the Keystone JSON string.
     *
     * @param   src  JSON string to parse.
     * @throws  IOException  JSON parsing IO error.
     */
    public void parse(String src) throws IOException {
        // Parse with Jackson library.
        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getJsonFactory();
        JsonParser jp = factory.createJsonParser(src);
        this.node =  mapper.readTree(jp).get("auth");
    } 

    /**
     * Get token from JSON.
     *
     * @return  Token string.
     */
    public String getToken() {
        return this.node.get("token").get("id").getTextValue();
    }

    /**
     * Get token expiration from JSON.
     *
     * @return  Token expiration string.
     */
    public String getTokenExpiration() {
        return this.node.get("token").get("expires").getTextValue();
    }

    /**
     * Get token tenant from JSON.
     *
     * @return  Token tenant string.
     */
    public String getTokenTenant() {
        return this.node.get("token").get("tenantId").getTextValue();
    }

    /**
     * Get user from JSON.
     *
     * @return  User string.
     */
    public String getUser() {
        return this.node.get("user").get("username").getTextValue();
    }

    /**
     * Get user tenant from JSON.
     *
     * @return  Tenant string.
     */
    public String getUserTenant() {
        return this.node.get("user").get("tenantId").getTextValue();
    }

    /**
     * Get user roles from JSON.
     *
     * @return  A string array of roles.
     */
    public String[] getUserRoles() {
        // Parse out roles from the JSON string and return as an array.
        JsonNode roleNode =  this.node.get("user").get("roleRefs");
        String[] roles = new String[roleNode.size()];
        Iterator<JsonNode> roleNodeItr = roleNode.getElements();
        int ii = 0;
        while (roleNodeItr.hasNext()){
            roleNode = roleNodeItr.next();
            roles[ii] = roleNode.get("roleId").getTextValue();
            ii++;
        }
        return roles;
    }
}

