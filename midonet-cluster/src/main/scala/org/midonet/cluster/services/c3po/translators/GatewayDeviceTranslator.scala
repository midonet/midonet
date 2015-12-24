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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.GatewayDevice
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.ROUTER_VTEP

class GatewayDeviceTranslator(protected val storage: ReadOnlyStorage,
                              protected val stateTableStorage: StateTableStorage)
    extends Translator[GatewayDevice] {
    import GatewayDeviceTranslator._

    override protected def translateCreate(gwDev: GatewayDevice)
    : OperationList = {
        // Only router VTEPs are supported.
        if (gwDev.getType != ROUTER_VTEP)
            throw new IllegalArgumentException(OnlyRouterVtepSupported)

        // Nothing to do other than pass the Neutron data through to ZK.
        List()
    }

    override protected def translateDelete(gwDevId: UUID): OperationList = {
        // Nothing to do but delete the Neutron data.
        List()
    }

    override protected def translateUpdate(gwDev: GatewayDevice)
    : OperationList = {
        // TODO: Implement.
        List()
    }
}

object GatewayDeviceTranslator {
    // TODO: Move to ValidationMessages.properties
    protected[translators] val OnlyRouterVtepSupported =
        "Only router_vtep gateway devices are supported."
}

