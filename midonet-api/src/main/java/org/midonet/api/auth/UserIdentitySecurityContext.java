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

import java.security.Principal;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.midonet.cluster.auth.UserIdentity;

/**
 * Security Context wrapper that uses UserIdentity class.
 */
public class UserIdentitySecurityContext implements SecurityContext {

    private Principal principal = null;
    private UserIdentity userIdentity = null;

    @Context
    UriInfo uriInfo;

    public UserIdentitySecurityContext(final UserIdentity userIdentity) {
        if (userIdentity != null) {
            principal = new Principal() {
                @Override
                public String getName() {
                    return userIdentity.tenantId;
                }
            };
        }
        this.userIdentity = userIdentity;
    }

    @Override
    public String getAuthenticationScheme() {
        return "token";
    }

    public UserIdentity getUserIdentity() {
        return userIdentity;
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isSecure() {
        // return "https".equals(uriInfo.getRequestUri().getScheme());
        return false;
    }

    @Override
    public boolean isUserInRole(String role) {
        return userIdentity != null && userIdentity.hasRole(role);
    }

    public void setUserIdentity(UserIdentity userIdentity) {
        this.userIdentity = userIdentity;
    }

}
