/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.cloudstack;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.midonet.api.HttpSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.core.MediaType;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * CloudStack API client
 */
public class CloudStackClient {

    private final static Logger log = LoggerFactory
            .getLogger(CloudStackClient.class);

    public final static int UserNotFoundHttpStatus = 431;
    public final static int UserLoginServerErrorHttpStatus = 531;

    private final CloudStackJsonParser parser;
    private final String apiUri;
    private final String apiKey;
    private final String secretKey;

    public CloudStackClient(String apiUri, String apiKey, String secretKey,
                            CloudStackJsonParser parser) {
        this.apiUri = apiUri;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.parser = parser;
    }

    private String getGetUserCommand(String userApiKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("command=getUser");
        sb.append("&response=json");     // Always JSON
        sb.append("&userapikey=");
        sb.append(userApiKey);
        sb.append("&apikey=");
        sb.append(apiKey);   // Admin key
        return sb.toString();
    }

    private String generateApiUri(String command)
            throws CloudStackClientException {
        StringBuilder sb = new StringBuilder();
        // TODO: Do more strict validation on the format of these entries.
        sb.append(apiUri);
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

        return StringUtils.join(encodedValues, '&');
    }

    private String generateBase64Sha1Digest(String command) throws
            CloudStackClientException {

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secret_key = new SecretKeySpec(
                    secretKey.getBytes(),"HmacSHA1");
            mac.init(secret_key);
            byte[] digest = mac.doFinal(command.getBytes());
            return new String(Base64.encodeBase64(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new CloudStackClientException("No algorithm found to do SHA-1: " + command, e);
        } catch (InvalidKeyException e) {
            throw new CloudStackClientException("Invalid secret key: " + secretKey, e);
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
            throw new CloudStackClientException("Error encoding command: " + command, ex);
        }
    }


    private String executeCommand(String commandUri) throws
            CloudStackConnectionException, CloudStackServerException {
        Client client = Client.create();
        WebResource resource = client.resource(commandUri);
        try {
            return resource.accept(MediaType.APPLICATION_JSON).get(
                    String.class);
        } catch (UniformInterfaceException e) {
            // Some server error occurred
            throw new CloudStackServerException("CloudStack server error.",
                    e, e.getResponse().getStatus());
        } catch (ClientHandlerException e) {
            throw new CloudStackConnectionException(
                    "Could not connect to CloudStack server. Url=" + commandUri,
                    e);
        }
    }

    /**
     * Get CloudStackUser object with user API key
     *
     * @param userApiKey User API key to get the user with.
     * @return CloudStackUser object
     * @throws CloudStackClientException
     * @throws CloudStackConnectionException
     * @throws CloudStackServerException
     */
    public CloudStackUser getUser(String userApiKey)
            throws CloudStackClientException, CloudStackConnectionException,
            CloudStackServerException {

        String command = getGetUserCommand(userApiKey);
        String uri = generateApiUri(command);

        String resp = null;
        try {
            resp = executeCommand(uri);
        } catch (CloudStackServerException e) {
            if (e.getStatus() == UserNotFoundHttpStatus) {
                // This indicates that the user does not exist
                log.warn("CloudStackService: User not found: {}", uri);
                return null;
            }
            throw e;
        }

        return (resp == null) ? null : parser.deserializeUser(resp);
    }
}
