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

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.midonet.brain.southbound.auth.UserIdentity;

/**
 * Class to implement auth request filter.
 */
public class AuthContainerRequestFilter implements ContainerRequestFilter {

    @Context
    HttpServletRequest hsr;

    /*
     * (non-Javadoc)
     *
     * @see
     * com.sun.jersey.spi.container.ContainerRequestFilter#filter(com.sun.jersey
     * .spi.container.ContainerRequest)
     */
    @Override
    public ContainerRequest filter(ContainerRequest req) {
        UserIdentity user = (UserIdentity) hsr
                .getAttribute(AuthFilter.USER_IDENTITY_ATTR_KEY);
        req.setSecurityContext(new UserIdentitySecurityContext(user));
        return req;
    }
}
