/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.auth.keystone.v2_0;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.typesafe.config.Config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthException;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.InvalidCredentialsException;
import org.midonet.cluster.auth.KeystoneAccess;
import org.midonet.cluster.auth.KeystoneAuthCredentials;
import org.midonet.cluster.auth.KeystoneTenant;
import org.midonet.cluster.auth.KeystoneTenantList;
import org.midonet.cluster.auth.Tenant;
import org.midonet.cluster.auth.Token;
import org.midonet.cluster.auth.UserIdentity;
import org.midonet.cluster.package$;

/**
 * Keystone Service.
 */
public class KeystoneService implements AuthService {

    private final static Logger log =
        LoggerFactory.getLogger(package$.MODULE$.keystoneLog());

    public final static String HEADER_X_AUTH_PROJECT = "X-Auth-Project";
    public final static String KEYSTONE_TOKEN_EXPIRED_FORMAT =
            "yyyy-MM-dd'T'HH:mm:ss'Z'";
    public final static String KEYSTONE_TOKEN_EXPIRED_FORMAT_WITH_MILLIS =
        "yyyy-MM-dd'T'HH:mm:ss.S'Z'";

    // The JDK8 APIs supposedly allow parsing formats with optional bits such as
    // "yyyy-MM-dd'T'HH:mm:ss[.S]'Z'" which is what we really want.  I
    // (Galo) have unfortunately had to give up on figuring out how to use those
    // APIs.
    private final static SimpleDateFormat dfDefault =
        new SimpleDateFormat(KEYSTONE_TOKEN_EXPIRED_FORMAT);

    private final static SimpleDateFormat dfWithMillis =
        new SimpleDateFormat(KEYSTONE_TOKEN_EXPIRED_FORMAT_WITH_MILLIS);

    private final static SimpleDateFormat[] dateFormats = {dfDefault,
                                                           dfWithMillis};

    static {
        for (SimpleDateFormat df : dateFormats) {
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
    }

    private final KeystoneClient client;
    private final KeystoneConfig config;

    @Inject
    public KeystoneService(Config config) {
        this.config = new KeystoneConfig(config);
        this.client = new KeystoneClient(this.config);
    }

    @VisibleForTesting
    static Date parseExpirationDate(String s)
        throws KeystoneInvalidFormatException {
        for (SimpleDateFormat df : dateFormats) {
            try {
                return df.parse(s);
            } catch (ParseException ex) {
                // bad luck, let's try more
            }
        }
        throw new KeystoneInvalidFormatException(
            "Unrecognizable keystone expired date format.");
    }

    @VisibleForTesting
    private String convertToAuthRole(String role) {
        String roleLowerCase = role.toLowerCase();
        if (roleLowerCase.equals(config.adminRole())) {
            return AuthRole.ADMIN;
        } else if (roleLowerCase.equals(config.tenantAdminRole())) {
            return AuthRole.TENANT_ADMIN;
        } else if (roleLowerCase.equals(config.tenantUserRole())) {
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

        UserIdentity userIdentity = new UserIdentity(
            access.getToken().getTenant().getId(),
            access.getToken().getTenant().getName(),
            access.getUser().getId(),
            access.getToken().getId());

        String r;
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
            Date expiration = parseExpirationDate(expiredSrc);
            token.setExpires(expiration);
        }

        return token;
    }

    private int parseLimit(HttpServletRequest request) {
        String limit = request.getParameter(KeystoneClient.LIMIT_QUERY);
        if (StringUtils.isEmpty(limit)) {
            return 0;
        }

        try {
            return Integer.parseInt(limit);
        } catch (RuntimeException ex) {
            log.warn("Invalid limit value passed in: " + limit);
            return 0;
        }
    }

    @Override
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException {
        log.debug("KeystoneService: entered getUserIdentityByToken. Token={}",
                token);

        if (StringUtils.isEmpty(token)) {
            // Don't allow empty token
            throw new InvalidCredentialsException("No token was passed in.");
        }

        KeystoneAccess access = null;

        try {
            access = client.getToken(token);
        } catch (KeystoneConnectionException kex) {
            log.warn("Authentication connection failed: "+ kex.getMessage());
        } catch (KeystoneServerException ex) {
            log.debug("Authentication failed: " + ex.getMessage());
            throw new InvalidCredentialsException("Problems with the token: "
                                                  + ex.getMessage());
        }
        // Parse the JSON response
        return (access == null) ? null : getUserIdentity(access);
    }

    @Override
    public Token login(String username, String password,
                       HttpServletRequest request)
            throws AuthException {

        // For Keystone, since we need a scoped token, project is required.
        String project = request.getHeader(HEADER_X_AUTH_PROJECT);

        if (StringUtils.isEmpty(project)) {
            // the project was not specified in the request, so we need
            // to try and obtain it from the config.
            project = this.config.tenantName();
            if (StringUtils.isEmpty(project)) {
                throw new InvalidCredentialsException("Project missing");
            }
        }

        // Construct the credentials
        KeystoneAuthCredentials credentials = new KeystoneAuthCredentials(
                username, password, project);

        KeystoneAccess access = null;
        try {
            // POST to get the token
            access = client.createToken(credentials);
        } catch (KeystoneConnectionException kex) {
            log.warn("Authentication connection failed: " + kex.getMessage());
        } catch (KeystoneServerException ex) {
            log.debug("Failed to get credentials: " + ex.getMessage());
            throw new InvalidCredentialsException("Keystone credentials error: " + ex.getMessage());
        }
        // Return the token
        return getToken(access);
    }

    /**
     * Gets a {@link Tenant} object from Keystone server.
     *
     * @param id Tenant ID
     * @return {@link Tenant} object
     * @throws AuthException
     */
    @Override
    public Tenant getTenant(String id) throws AuthException {
        log.debug("KeystoneService.getTenant entered.  id: " + id);

        KeystoneTenant tenant = client.getTenant(id);
        return (tenant == null) ? null : tenant.getTenant();
    }

    /**
     * Gets a list of tenants from the Keystone identity service, and returns
     * the result in a list of {@link Tenant} objects.
     *
     * @param request Keystone API v2.0 accepts the following query string
     *                parameters:
     *                <ul>
     *                <li>marker: ID of the last tenant in the previous request.
     *                The result set from this request starts from the tenant
     *                whose ID is after this one.</li>
     *                <li>limit: Number of tenants to fetch.</li>
     *                </ul>
     *                All other request fields are ignored.
     * @return  A list of {@link Tenant} objects representing the tenants in
     *          Keystone.
     * @throws AuthException
     */
    @Override
    public List<? extends Tenant> getTenants(HttpServletRequest request)
            throws AuthException {
        log.debug("KeystoneService.getTenants entered.  Request: " + request);

        // Parse out marker and limit
        String marker = request.getParameter(KeystoneClient.MARKER_QUERY);
        int limit = parseLimit(request);

        KeystoneTenantList tenantList = client.getTenants(marker, limit);

        log.debug("KeystoneService.getTenants exiting.  "
                + tenantList.getTenants().size()
                + " tenants found with marker = " + marker + ", limit = ",
                + limit);
        return tenantList.get();
    }
}
