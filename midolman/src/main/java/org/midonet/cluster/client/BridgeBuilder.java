/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import scala.Option;

public interface BridgeBuilder extends DeviceBuilder<BridgeBuilder> {
    void setTunnelKey(long key);
    void removeMacLearningTable(short vlanId);
    Set<Short> vlansInMacLearningTable();
    void setMacLearningTable(short vlanId, MacLearningTable table);
    void setIp4MacMap(IpMacMap<IPv4Addr> m);
    void setLogicalPortsMap(Map<MAC, UUID> macToLogicalPortId,
                            Map<IPAddr, MAC> ipToMac);
    void setVlanBridgePeerPortId(Option<UUID> id);
    void setVlanPortMap(VlanPortMap vlanPortMap);
}
