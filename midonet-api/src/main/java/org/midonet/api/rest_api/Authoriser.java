/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.api.rest_api;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

abstract public class Authoriser {

    final SecurityContext context;

    public Authoriser(SecurityContext context) {
        this.context = context;
    }

    abstract public Router tryAuthoriseRouter(UUID routerId, String what)
        throws StateAccessException, SerializationException;

    abstract public Bridge tryAuthoriseBridge(UUID bridgeId, String what)
        throws StateAccessException, SerializationException;

    abstract public Port tryAuthorisePort(UUID portId, String what)
        throws StateAccessException, SerializationException;

    abstract public PortGroup tryAuthorisePortGroup(UUID pgId, String what)
        throws StateAccessException, SerializationException;

    abstract public Chain tryAuthoriseChain(UUID chainId, String what)
        throws StateAccessException, SerializationException;

    abstract public BGP tryAuthoriseBgp(UUID bgpId, String what)
        throws StateAccessException, SerializationException;

    abstract public Rule tryAuthoriseRule(UUID ruleId, String what)
        throws StateAccessException, SerializationException;

    abstract public AdRoute tryAuthoriseAdRoute(UUID id, String what)
        throws StateAccessException, SerializationException;

    public boolean isAdmin() {
        return (context.isUserInRole(AuthRole.ADMIN));
    }

    public <T> boolean isOwner(T tenantId) {
        return context.getUserPrincipal().getName().equals(tenantId);
    }

    public <T> boolean isAdminOrOwner(T tenantId) {
        return isAdmin() || isOwner(tenantId);
    }
}


