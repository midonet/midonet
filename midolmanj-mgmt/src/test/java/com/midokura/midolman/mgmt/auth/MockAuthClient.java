/*
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.auth;

import javax.servlet.FilterConfig;

/**
 * Mock auth client.
 */
public final class MockAuthClient implements AuthClient {

    private final UserIdentity testUserIdentity;

    /**
     * Create a MockAuthClient object.
     *
     * @param config
     *            FilterConfig object.
     */
    public MockAuthClient(FilterConfig config) {
        // For now, always return the same data.
        testUserIdentity = new UserIdentity();
        testUserIdentity.setTenantId("bf3cc554bcca4b89aab4e383eec3d916");
        testUserIdentity.setTenantName("demo");
        testUserIdentity.setUserId("eec1b17d499d4ccaad250e49c7bc4c29");
        testUserIdentity.setToken("999888777666");
        testUserIdentity.addRole(AuthRole.ADMIN);
    }

    /**
     * Return a UserIdentity object.
     *
     * @param dummyToken
     *            Not used.
     * @return UserIdentity object.
     */
    @Override
    public UserIdentity getUserIdentityByToken(String dummyToken) {
        return testUserIdentity;
    }
}
