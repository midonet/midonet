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

package org.midonet.brain.services.rest_api.network.auth;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;

import org.midonet.brain.services.rest_api.auth.AuthAction;
import org.midonet.brain.services.rest_api.auth.Authorizer;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.util.serialization.SerializationException;

import static org.slf4j.LoggerFactory.getLogger;


public abstract class PortAuthorizer extends Authorizer<UUID> {

    private final static Logger log = getLogger(PortAuthorizer.class);

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException,
                                             SerializationException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        String tenantId = null;
        try {
            tenantId = getTenantId(id);
        } catch (NullPointerException ex) {
            // Not pretty, but allows removing the dependency on the cluster
            // .data package and move the Authorizer to Brain
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }

        return isOwner(context, tenantId);
    }

    protected abstract String getTenantId(UUID portId) throws
                                                       StateAccessException,
                                                       SerializationException;

}
