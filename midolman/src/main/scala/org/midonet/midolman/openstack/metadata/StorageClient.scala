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

package org.midonet.midolman.openstack.metadata

import java.util.UUID

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.util.concurrent._

class StorageClient(val store: ReadOnlyStorage) {

    def getComputePortInfo(portId: UUID): Option[InstanceInfo] = {
        val port = try {
            store.get(classOf[NeutronPort], portId).await()
        } catch {
            case e: NotFoundException =>
                Log debug s"Non-neutron port: $e"
                return None
        }
        if (port.getDeviceOwner != NeutronPort.DeviceOwner.COMPUTE) {
            return None
        }
        if (port.getFixedIpsCount != 1) {
            return None
        }
        val info = InstanceInfo(
            port.getFixedIps(0).getIpAddress.getAddress,
            port.getMacAddress,
            portId,
            port.getTenantId,
            port.getDeviceId)
        Log debug s"Instance information: $info"
        Some(info)
    }
}
