/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.cloudstack;

import com.google.inject.Inject;
import org.midonet.api.HttpSupport;
import org.midonet.api.auth.AuthClient;
import org.midonet.api.auth.AuthException;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.UserIdentity;
import org.midonet.util.StringUtil;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import org.apache.commons.codec.binary.Base64;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * CloudStack Client.
 */
public class CloudStackClient implements AuthClient {

    private final static Logger log = LoggerFactory
            .getLogger(CloudStackClient.class);

    private final CloudStackConfig config;
    private final static int UserNotFoundHttpStatus = 431;

     /**
      * Create a CloudStackClient object from a CloudStackConfig object.
     *
     * @param config
     *            CloudStackConfig object.
     */
    @Inject
    public CloudStackClient(CloudStackConfig config) {
        this.config = config;
    }

    private String convertToAuthRole(int type) {
        switch(type) {
            case 0:
                return AuthRole.TENANT_ADMIN;
            case 1:
                return AuthRole.ADMIN;
            default:
                return null;
        }
    }

    private String getGetUserCommand(String apiKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("command=getUser");
        sb.append("&response=json");     // Always JSON
        sb.append("&userapikey=");
        sb.append(apiKey);
        sb.append("&apikey=");
        sb.append(config.getApiKey());   // Admin key
        return sb.toString();
    }

    private String generateApiUri(String apiKey)
            throws CloudStackClientException {
        StringBuilder sb = new StringBuilder();
        // TODO: Do more strict validation on the format of these entries.
        sb.append(config.getApiBaseUri());
        sb.append(config.getApiPath());

        String command = getGetUserCommand(apiKey);
        sb.append(command);

        sb.append("&signature=");
        sb.append(generateSignature(command));

        return sb.toString();
    }

    private String urlEncodeAndSortCommand(String command)
            throws UnsupportedEncodingException {

        String[] pairs = command.split("&");

        // For each value, make sure that it is URL encoded
        SortedSet<String> encodedValues = new TreeSet<String>();
        for (String pair : pairs) {
            String[] elems = pair.split("=");
            if (elems.length != 2) {
                continue;
            }

            String item = elems[0] + "=" + URLEncoder.encode(elems[1],
                    HttpSupport.UTF8_ENC);

            // Lower case the field + value and add to the sorted Set
            encodedValues.add(item.toLowerCase());
        }

        return StringUtil.join(encodedValues, '&');
    }

    private String generateBase64Sha1Digest(String command) throws
            CloudStackClientException {

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secret_key = new SecretKeySpec(
                    config.getSecretKey().getBytes(),"HmacSHA1");
            mac.init(secret_key);
            byte[] digest = mac.doFinal(command.getBytes());
            return new String(Base64.encodeBase64(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new CloudStackClientException("No algorithm found to do " +
                    "SHA-1: " + command);
        } catch (InvalidKeyException e) {
            throw new CloudStackClientException("Invalid secret key: " +
                    config.getSecretKey());
        }
    }

    private String generateSignature(String command)
            throws CloudStackClientException {

        try {
            // Encode the values
            String encodedValueCommand = urlEncodeAndSortCommand(command);

            // Get the Base64 of SHA-1 digest
            String base64Digest = generateBase64Sha1Digest(encodedValueCommand);

            // Make sure the signature is URL safe
            return URLEncoder.encode(base64Digest, HttpSupport.UTF8_ENC);
        } catch (UnsupportedEncodingException ex) {
            throw new CloudStackClientException("Error encoding command: " +
                    command);
        }
    }

    private String executeCloudStackApiRequest(String commandUri) throws
            CloudStackServerException, CloudStackConnectionException {
        Client client = Client.create();
        WebResource resource = client.resource(commandUri);

        try {
            return resource.accept(MediaType.APPLICATION_JSON).get(
                    String.class);
        } catch (UniformInterfaceException e) {
            if (e.getResponse().getStatus() == UserNotFoundHttpStatus) {
                // This indicates that the user does not exist
                log.warn("CloudStackClient: User not found: {}", commandUri);
                return null;
            }
            // Some server error occurred
            throw new CloudStackServerException("CloudStack server error.", e);
        } catch (ClientHandlerException e) {
            throw new CloudStackConnectionException(
                    "Could not connect to CloudStack server. Url="
                            + commandUri, e);
        }
    }

    private UserIdentity jsonNodeToUserIdentity(JsonNode rootNode) {

        UserIdentity user = new UserIdentity();
        // TODO: handle changes to JSON format better
        JsonNode userNode = rootNode.get("getuserresponse").get("user");
        user.setTenantId(userNode.get("accountid").getTextValue());
        user.setTenantName(userNode.get("account").getTextValue());
        user.setUserId(userNode.get("id").getTextValue());
        user.setToken(userNode.get("apikey").getTextValue());
        user.addRole(
                convertToAuthRole(userNode.get("accounttype").getIntValue()));

        return user;
    }

    private UserIdentity parseJson(String response)
            throws CloudStackClientException {

        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getJsonFactory();
        JsonNode rootNode = null;
        try {
            JsonParser jp = factory.createJsonParser(response);
            rootNode = mapper.readTree(jp);
        } catch (IOException e) {
            throw new CloudStackClientException(
                    "Could not parse CloudStack response.", e);
        }

        return jsonNodeToUserIdentity(rootNode);
    }

    /**
     * Authenticate using the API key of the user.
     *
     * @param apiKey
     *            API key
     * @return UserIdentity object.
     * @throws AuthException
     */
    @Override
    public UserIdentity getUserIdentityByToken(String apiKey)
            throws AuthException {
        log.debug("CloudStackClient: entered getUserIdentityByToken.  " +
                "ApiKey={}", apiKey);

        // Construct the getUser command API
        String getUserCommandUri = generateApiUri(apiKey);

        // Make the query to the server
        String response = executeCloudStackApiRequest(getUserCommandUri);

        // Parse the JSON response
        return (response == null) ? null : parseJson(response);
    }
 }
