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
package org.midonet.api.filter.auth;

import com.google.inject.Inject;

import org.midonet.brain.services.rest_api.auth.AuthAction;
import org.midonet.brain.services.rest_api.auth.Authorizer;
import org.midonet.util.serialization.SerializationException;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Chain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

public class ChainAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(ChainAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public ChainAuthorizer(DataClient dataClient) {
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

        Chain chain = dataClient.chainsGet(id);
        if (chain == null) {
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        } else {
            return isOwner(context, chain.getProperty(
                    Chain.Property.tenant_id));
        }
    }
}
