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
package org.midonet.api.auth.cloudstack;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.*;

/**
 * CloudStackAuthService Client.
 */
public class CloudStackAuthService implements AuthService {

    private final static Logger log = LoggerFactory
            .getLogger(CloudStackAuthService.class);

    private final CloudStackClient client;

    /**
     * Create a CloudStackAuthService object from a CloudStackConfig object.
     *
     * @param client
     *            CloudStackClient object.
     */
    @Inject
    public CloudStackAuthService(CloudStackClient client) {
        this.client = client;
    }

    private UserIdentity getUserIdentity(CloudStackUser user) {
        log.debug("CloudStackAuthService: entered getUserIdentity.  " +
                "CloudStackUser={}", user);

        UserIdentity userIdentity = new UserIdentity();
        userIdentity.setToken(user.getApiKey());
        userIdentity.setUserId(user.getId());
        userIdentity.setTenantName(user.getAccount());
        userIdentity.setTenantId(user.getAccountId());

        if (user.isAdmin()) {
            userIdentity.addRole(AuthRole.ADMIN);
        } else {
            userIdentity.addRole(AuthRole.TENANT_ADMIN);
        }

        log.debug("CloudStackAuthService: exiting getUserIdentity.  " +
                "UserIdentity={}", userIdentity);
        return userIdentity;
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
        log.debug("CloudStackAuthService: entered getUserIdentityByToken.  " +
                "ApiKey={}", apiKey);

        if (StringUtils.isEmpty(apiKey)) {
            // Don't allow empty apiKey
            throw new InvalidCredentialsException("No apiKey was passed in.");
        }

        // Get user
        CloudStackUser user = client.getUser(apiKey);

        // Get UserIdentity
        return (user == null || user.isDisabled()) ? null :
                getUserIdentity(user);
    }

    @Override
    public Token login(String username, String password,
                        HttpServletRequest request) throws AuthException {
        throw new UnsupportedOperationException("Cloudstack auth does not " +
                "support login.");
    }

    @Override
    public Tenant getTenant(String id) throws AuthException {
        throw new UnsupportedOperationException("Cloudstack auth does not " +
                "support getTenant.");
    }

    @Override
    public List<Tenant> getTenants(HttpServletRequest request)
            throws AuthException {
        throw new UnsupportedOperationException("Cloudstack auth does not " +
                "support getTenants.");
    }
}
