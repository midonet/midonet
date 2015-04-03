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

import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.api.network.Port;
import org.midonet.api.network.PortFactory;
import org.midonet.api.network.RouterPort;
import org.midonet.brain.services.rest_api.network.auth.PortAuthorizer;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Router;
import org.midonet.util.serialization.SerializationException;

public class DataClientPortAuthorizer extends PortAuthorizer {

    private final DataClient dataClient;

    @Inject
    public DataClientPortAuthorizer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    protected String getTenantId(UUID portId) throws
                                              StateAccessException,
                                              SerializationException {
        Port port = PortFactory.convertToApiPort(dataClient.portsGet(portId));
        if (port instanceof RouterPort) {
            return dataClient.routersGet(port.getDeviceId())
                .getProperty(Router.Property.tenant_id);
        } else {
            return dataClient.bridgesGet(port.getDeviceId())
                .getProperty(Bridge.Property.tenant_id);
        }
    }
}
