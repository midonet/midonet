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
package org.midonet.brain.services.rest_api.network.auth;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import com.google.inject.Inject;

import org.slf4j.Logger;

import org.midonet.brain.services.rest_api.auth.AuthAction;
import org.midonet.brain.services.rest_api.auth.Authorizer;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Router.Property;
import org.midonet.util.serialization.SerializationException;

import static org.slf4j.LoggerFactory.getLogger;

public class RouterAuthorizer extends Authorizer<UUID> {

    private final static Logger log = getLogger(RouterAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public RouterAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public boolean authorize(SecurityContext context, AuthAction action,
                             UUID id) throws StateAccessException,
                                             SerializationException {
        log.debug("authorize entered: id=" + id + ",action=" + action);

        if (isAdmin(context)) {
            return true;
        }

        Router router = dataClient.routersGet(id);
        if (router == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } else {
            return isOwner(context, router.getProperty(Property.tenant_id));
        }
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
