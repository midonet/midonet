/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone.v2_0;

import org.midonet.api.auth.*;
import org.midonet.api.auth.keystone.KeystoneConfig;
import org.midonet.api.auth.keystone.KeystoneInvalidFormatException;
import org.midonet.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Keystone Service.
 */
public class KeystoneService implements AuthService {

    private final static Logger log = LoggerFactory
            .getLogger(KeystoneService.class);

    public final static String HEADER_X_AUTH_PROJECT = "X-Auth-Project";
    public final static String KEYSTONE_TOKEN_EXPIRED_FORMAT =
            "yyyy-MM-dd'T'HH:mm:ss'Z'";

    private final KeystoneClient client;
    private final KeystoneConfig config;

    /**
     * Create a KeystoneService object from a KeystoneConfig object.
     *
     * @param client
     *            KeystoneClient object
     * @param config
     *            KeystoneConfig object.
     */
    public KeystoneService(KeystoneClient client, KeystoneConfig config) {
        this.client = client;
        this.config = config;
    }

    private String convertToAuthRole(String role) {
        String roleLowerCase = role.toLowerCase();
        if (roleLowerCase.equals(config.getAdminRole())) {
            return AuthRole.ADMIN;
        } else if (roleLowerCase.equals(config.getTenantAdminRole())) {
            return AuthRole.TENANT_ADMIN;
        } else if (roleLowerCase.equals(config.getTenantUserRole())) {
            return AuthRole.TENANT_USER;
        } else {
            // Unknown roles are ignored.
            return null;
        }
    }

    private UserIdentity getUserIdentity(KeystoneAccess authAccess) {
        log.debug("KeystoneService.getUserIdentity entered.");

        KeystoneAccess.Access access = authAccess.getAccess();
        if (access.getUser() == null || access.getUser().getRoles() == null) {
            throw new IllegalArgumentException(
                    "User information is missing from this access object.");
        }

        if (access.getToken() == null
                || access.getToken().getTenant() == null) {
            throw new IllegalArgumentException(
                    "Tenant information is missing from this access object.");
        }

        UserIdentity userIdentity = new UserIdentity();
        userIdentity.setTenantId(access.getToken().getTenant().getId());
        userIdentity.setTenantName(access.getToken().getTenant().getName());
        userIdentity.setToken(access.getToken().getId());
        userIdentity.setUserId(access.getUser().getId());

        String r = null;
        for(KeystoneAccess.Access.User.Role role : access.getUser().getRoles()){
            r = convertToAuthRole(role.getName());
            if (r != null) {
                userIdentity.addRole(r);
            }
        }

        log.debug("KeystoneService.getUserIdentity exiting: {}", userIdentity);
        return userIdentity;
    }

    private Token getToken(KeystoneAccess access)
            throws KeystoneInvalidFormatException {

        Token token = new Token();
        token.setKey(access.getAccess().getToken().getId());

        // Make sure the expired is converted to Date
        String expiredSrc = access.getAccess().getToken().getExpires();
        if (expiredSrc != null) {
            DateFormat df = new SimpleDateFormat(KEYSTONE_TOKEN_EXPIRED_FORMAT);
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            try {
                token.setExpires(df.parse(expiredSrc));
            } catch (ParseException e) {
                throw new KeystoneInvalidFormatException(
                        "Unrecognizable keystone expired date format.");
            }
        }

        return token;
    }

    @Override
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException {
        log.debug("KeystoneService: entered getUserIdentityByToken. Token={}",
                token);

        if (StringUtil.isNullOrEmpty(token)) {
            // Don't allow empty token
            throw new InvalidCredentialsException("No token was passed in.");
        }

        KeystoneAccess access = client.getToken(token);

        // Parse the JSON response
        return (access == null) ? null : getUserIdentity(access);
    }

    @Override
    public Token login(String username, String password,
                           HttpServletRequest request)
            throws AuthException {

        // For Keystone, since we need a scoped token, project is required.
        String project = request.getHeader(HEADER_X_AUTH_PROJECT);
        if (StringUtil.isNullOrEmpty(project)) {
            throw new InvalidCredentialsException("Project missing");
        }

        // Construct the credentials
        KeystoneAuthCredentials credentials = new KeystoneAuthCredentials(
                username, password, project);

        // POST to get the token
        KeystoneAccess access = client.createToken(credentials);

        // Return the token
        return getToken(access);
    }
}
