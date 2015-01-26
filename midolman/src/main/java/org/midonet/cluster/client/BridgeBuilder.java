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

package org.midonet.cluster.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import scala.Option;

import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

public interface BridgeBuilder extends DeviceBuilder<BridgeBuilder> {
    void setTunnelKey(long key);
    void removeMacLearningTable(short vlanId);
    Set<Short> vlansInMacLearningTable();
    void setMacLearningTable(short vlanId, MacLearningTable table);
    void setIp4MacMap(IpMacMap<IPv4Addr> m);
    void setLogicalPortsMap(Map<MAC, UUID> macToLogicalPortId,
                            Map<IPAddr, MAC> ipToMac);
    void setVlanBridgePeerPortId(Option<UUID> id);
    void setExteriorVxlanPortIds(List<UUID> ids);
    void setVlanPortMap(VlanPortMap vlanPortMap);
    void updateMacEntry(short vlanId, MAC mac, UUID oldPort, UUID newPort);
    void setExteriorPorts(List<UUID> ports);
}
