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

package org.midonet.midolman.topology

import java.util.UUID

import org.midonet.midolman.state.{PathBuilder, ZkManager}
import org.midonet.midolman.simulation.Bridge.UntaggedVlanId

@Deprecated
trait LegacyBuilder {
    @Deprecated
    def ensureLegacyBridge(zk: ZkManager, path: PathBuilder, bridgeId: UUID)
    : Unit = {
        zk.addPersistent(path.getBridgePath(bridgeId), null)
        zk.addPersistent(path.getBridgeMacPortsPath(bridgeId, UntaggedVlanId),
                         null)
        zk.addPersistent(path.getBridgeVlansPath(bridgeId), null)
    }
    @Deprecated
    def ensureLegacyBridgeVlan(zk: ZkManager, path: PathBuilder, bridgeId: UUID,
                               vlanId: Short): Unit = {
        zk.addPersistent(path.getBridgeVlanPath(bridgeId, vlanId), null)
        zk.addPersistent(path.getBridgeMacPortsPath(bridgeId, vlanId), null)
    }
    //def ensureLegacyRouter(zk: ZkManager, path: PathBuilder, router)
}
