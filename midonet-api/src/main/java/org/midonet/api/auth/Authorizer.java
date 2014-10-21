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

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.ws.rs.core.SecurityContext;
import java.io.Serializable;

/**
 * Base class for authorization service.
 */
public abstract class Authorizer <T extends Serializable> {

    public abstract boolean authorize(SecurityContext context,
                                      AuthAction action, T id)
            throws StateAccessException, SerializationException;

    public static boolean isAdmin(SecurityContext context) {
        return (context.isUserInRole(AuthRole.ADMIN));
    }

    public static <T> boolean isOwner(SecurityContext context, T id) {
        return context.getUserPrincipal().getName().equals(id);
    }

    public static <T> boolean isAdminOrOwner(SecurityContext context, T id) {
        return isAdmin(context) || isOwner(context, id);
    }

}
