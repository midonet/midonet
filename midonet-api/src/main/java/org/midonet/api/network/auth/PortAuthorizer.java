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
package org.midonet.api.network.auth;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.network.Port;
import org.midonet.api.network.PortFactory;
import org.midonet.api.network.RouterPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

/**
 * Authorizer for Port
 */
public class PortAuthorizer extends Authorizer<UUID> {

    private final static Logger log = LoggerFactory
            .getLogger(PortAuthorizer.class);

    private final DataClient dataClient;

    @Inject
    public PortAuthorizer(DataClient dataClient) {
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

        try {
            org.midonet.cluster.data.Port<?, ?> portData =
                    dataClient.portsGet(id);
            if (portData == null) {
                log.warn("Attempted to authorize a non-existent resource: {}",
                        id);
                return false;
            }

            Port port = PortFactory.convertToApiPort(portData);
            String tenantId = null;
            if (port instanceof RouterPort) {
                Router router = dataClient.routersGet(port.getDeviceId());
                tenantId = router.getProperty(Router.Property.tenant_id);
            } else {
                Bridge bridge =  dataClient.bridgesGet(port.getDeviceId());
                tenantId = bridge.getProperty(Bridge.Property.tenant_id);
            }
            return isOwner(context, tenantId);
        } catch (NoStatePathException ex) {
            // get throws an exception when the state is not found.
            log.warn("Attempted to authorize a non-existent resource: {}", id);
            return false;
        }
    }
}
