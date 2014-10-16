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
package org.midonet.cluster.data;

import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;

/**
 * Factory class for creating / converting {@link Port} instances
 */
public class Ports {

    public static BridgePort bridgePort(Bridge bridge) {
        return new BridgePort().setDeviceId(bridge.getId());
    }

    public static BridgePort bridgePort(Bridge bridge, Short vlanId) {
        return new BridgePort()
                   .setDeviceId(bridge.getId())
                   .setVlanId(vlanId);
    }

    public static RouterPort routerPort(Router router) {
        return new RouterPort().setDeviceId(router.getId());
    }

}
