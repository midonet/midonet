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
package org.midonet.cluster.services.c3po

import org.midonet.cluster.models.Devices.Network
import org.midonet.cluster.models.Neutron.NeutronNetwork

/**
 * Defines the conversion logic.
 */
object NetworkConverter {
    implicit def toMido(network: NeutronNetwork): Network = {
       Network.newBuilder()
              .setId(network.getId)
              .setTenantId(network.getTenantId)
              .setName(network.getName)
              .setAdminStateUp(network.getAdminStateUp)
              .build
    }
}
