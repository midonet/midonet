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
package org.midonet.api.auth;

import java.util.List;
import javax.servlet.http.HttpServletRequest;

/**
 * This class is for testing purpose.
 * Used by the {@code TestAuthServiceProvider} to test the fallback mechanism
 */
public class FakeTestAuthService implements AuthService {
    @Override
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException {
        return null;
    }

    @Override
    public Token login(String username, String password,
                       HttpServletRequest request)
            throws AuthException {
        return null;
    }

    @Override
    public Tenant getTenant(String id) throws AuthException {
        return null;
    }

    @Override
    public List<Tenant> getTenants(HttpServletRequest request)
            throws AuthException {
        return null;
    }
}
