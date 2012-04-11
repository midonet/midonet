/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configurable auth client that skips authentication but allows setting of
 * roles.
 */
public final class NoAuthClient implements AuthClient {

    private final static Logger log = LoggerFactory
            .getLogger(NoAuthClient.class);
    private final Map<String, UserIdentity> tokenMap;
    public static final String ADMIN_TOKEN_KEY = "admin_tokens";
    public static final String TENANT_ADMIN_TOKEN_KEY = "tenant_admin_tokens";
    public static final String TENANT_USER_TOKEN_KEY = "tenant_user_tokens";

    /**
     * Create a NoAuthClient object.
     *
     * @param config
     *            FilterConfig object.
     */
    public NoAuthClient(FilterConfig config) {
        tokenMap = new HashMap<String, UserIdentity>();
        String tokens = config.getInitParameter(ADMIN_TOKEN_KEY);
        if (tokens != null) {
            setRoles(tokens, AuthRole.ADMIN);
        }

        tokens = config.getInitParameter(TENANT_ADMIN_TOKEN_KEY);
        if (tokens != null) {
            setRoles(tokens, AuthRole.TENANT_ADMIN);
        }

        tokens = config.getInitParameter(TENANT_USER_TOKEN_KEY);
        if (tokens != null) {
            setRoles(tokens, AuthRole.TENANT_USER);
        }
    }

    private UserIdentity createUserIdentity() {
        UserIdentity userIdentity = new UserIdentity();
        userIdentity.setTenantId("no_auth_tenant_id");
        userIdentity.setTenantName("no_auth_tenant_name");
        userIdentity.setUserId("no_auth_user");
        userIdentity.setToken("no_auth_token");
        return userIdentity;
    }

    private void setRoles(String tokenStr, String role) {
        String[] tokens = tokenStr.split(",");
        for (String token : tokens) {
            String tok = token.trim();
            if (tok.length() > 0) {
                UserIdentity identity = tokenMap.get(tok);
                if (identity == null) {
                    identity = createUserIdentity();
                    tokenMap.put(tok, identity);
                }
                identity.addRole(role);
            }
        }
    }

    /**
     * Return a UserIdentity object.
     *
     * @param token
     *            Token to use to get the roles.
     * @return UserIdentity object.
     */
    @Override
    public UserIdentity getUserIdentityByToken(String token) {
        log.debug("NoAuthClient.getUserIdentityByToken entered. {}", token);

        UserIdentity user = tokenMap.get(token);
        if (user == null) {
            // For backward compatibility, no token == admin privilege.
            user = createUserIdentity();
            user.addRole(AuthRole.ADMIN);
        }

        log.debug("NoAuthClient.getUserIdentityByToken exiting. {}", user);
        return user;
    }
}
