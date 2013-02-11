/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.cloudstack;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * JSON parser for CloudStack response string
 */
public class CloudStackJsonParser {

    private final static Logger log = LoggerFactory
            .getLogger(CloudStackJsonParser.class);

    ObjectMapper mapper = new ObjectMapper();
    JsonFactory factory = mapper.getJsonFactory();

    /**
     * Parse JSON string to construct CloudStackUser object
     *
     * @param response JSON string
     * @return CloudStackUser object
     * @throws CloudStackClientException
     */
    public CloudStackUser deserializeUser(String response)
            throws CloudStackClientException {
        log.debug("CloudStackJsonParser.deserializeUser entered: " +
                "response = {}", response);

        JsonNode rootNode = null;
        try {
            JsonParser jp = factory.createJsonParser(response);
            rootNode = mapper.readTree(jp);
        } catch (IOException e) {
            throw new CloudStackClientException(
                    "Could not parse CloudStack response.", e);
        }

        CloudStackUser user = new CloudStackUser();
        JsonNode userNode = rootNode.get("getuserresponse").get("user");

        user.setAccountId(userNode.get("accountid").getTextValue());
        user.setAccount(userNode.get("account").getTextValue());
        user.setId(userNode.get("id").getTextValue());
        user.setApiKey(userNode.get("apikey").getTextValue());
        user.setAccountType(userNode.get("accounttype").getIntValue());

        log.debug("CloudStackJsonParser.deserializeUser exiting: " +
                "CloudStackUser = {}", user);
        return user;
    }
}
