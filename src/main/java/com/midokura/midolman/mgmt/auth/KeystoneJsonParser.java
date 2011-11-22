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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON parser for Keystone response.
 *
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public final class KeystoneJsonParser {
    /*
     * Wrapper for Jackson JSON Parser to parse the response from Keystone.
     */

    private final static Logger log = LoggerFactory
            .getLogger(KeystoneJsonParser.class);

    /**
     * Default constructor.
     */
    public KeystoneJsonParser() {
    }

    /**
     * Parse the Keystone JSON string.
     *
     * @param src
     *            JSON string to parse.
     * @throws IOException
     *             JSON parsing IO error.
     */
    public static TenantUser parse(String src) throws IOException {
        log.debug("Keystone replied: " + src);
        // Parse with Jackson library.
        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getJsonFactory();
        JsonParser jp = factory.createJsonParser(src);
        JsonNode node = mapper.readTree(jp).get("access");

        TenantUser tu = new TenantUser();
        tu.setUserId(node.get("user").get("username").getTextValue());
        String tenantId = node.get("token").get("tenant").get("id")
                .getTextValue();
        tu.setTenantId(tenantId);
        tu.setTenantName(node.get("token").get("tenant").get("name")
                .getTextValue());
        tu.setToken(node.get("token").get("id").getTextValue());

        JsonNode roleNode = node.get("user").get("roles");
        Iterator<JsonNode> roleNodeItr = roleNode.getElements();
        while (roleNodeItr.hasNext()) {
            roleNode = roleNodeItr.next();
            tu.addRole(roleNode.get("name").getTextValue());
        }

        return tu;
    }
}
