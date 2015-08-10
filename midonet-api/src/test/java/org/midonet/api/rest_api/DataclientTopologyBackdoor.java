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

package org.midonet.api.rest_api;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostDirectory.VirtualPortMapping;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.cluster.data.storage.ArpCacheEntry;
import org.midonet.midolman.state.ArpTable;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

public class DataclientTopologyBackdoor implements TopologyBackdoor {

    @Inject
    HostZkManager hostZkManager;

    @Inject
    RouterZkManager routerZkManager;

    @Inject
    DataClient dataClient;

    @Override
    public void addArpTableEntryToRouter(UUID routerId, IPv4Addr ip, MAC mac) {
        try {
            new ArpTable(routerZkManager.getArpTableDirectory(routerId))
                .put(ip, new ArpCacheEntry(mac, 1000, 1000, 3000));
        } catch (StateAccessException e) {
            throw new RuntimeException("Cannot create the ARP table entry", e);
        }
    }

    @Override
    public void createHost(UUID hostId, String name, InetAddress[] addresses) {
        try {
            HostDirectory.Metadata metadata = new HostDirectory.Metadata();
            metadata.setName(name);
            metadata.setAddresses(addresses);
            hostZkManager.createHost(hostId, metadata);
        } catch (SerializationException | StateAccessException e) {
            throw new RuntimeException("Cannot create the host", e);
        }
    }

    @Override
    public void makeHostAlive(UUID hostId) {
        try {
            hostZkManager.makeAlive(hostId);
        } catch (StateAccessException e) {
            throw new RuntimeException("Cannot make the host alive", e);
        }
    }

    @Override
    public List<UUID> getHostIds() {
        try {
            return hostZkManager.getHostIds();
        } catch (StateAccessException e) {
            throw new RuntimeException("Cannot get host ids", e);
        }
    }

    @Override
    public Integer getFloodingProxyWeight(UUID hostId) {
        try {
            return hostZkManager.getFloodingProxyWeight(hostId);
        } catch (SerializationException | StateAccessException e) {
            throw new RuntimeException("Cannot get flooding proxy weight", e);
        }
    }

    @Override
    public void setFloodingProxyWeight(UUID hostId, int weight) {
        try {
            hostZkManager.setFloodingProxyWeight(hostId, weight);
        } catch (SerializationException | StateAccessException e) {
            throw new RuntimeException("Cannot set flooding proxy weight", e);
        }
    }

    @Override
    public void createInterface(UUID hostId, String name, MAC mac, int mtu,
                                InetAddress[] addresses) {
        try {
            HostDirectory.Interface ifc = new HostDirectory.Interface();
            ifc.setName(name);
            ifc.setMac(mac.getAddress());
            ifc.setMtu(mtu);
            ifc.setType(HostDirectory.Interface.Type.Physical);
            ifc.setAddresses(addresses);
            hostZkManager.createInterface(hostId, ifc);
        } catch (IOException | SerializationException |
                 StateAccessException e) {
            throw new RuntimeException("Cannot create the interface", e);
        }
    }

    @Override
    public void addVirtualPortMapping(UUID hostId, UUID portId,
                                      String ifcName) {
        try {
            hostZkManager.addVirtualPortMapping(hostId,
                                                new VirtualPortMapping(portId,
                                                                       ifcName));

        } catch (SerializationException | StateAccessException e) {
            throw new RuntimeException("Cannot create the port mapping", e);
        }
    }

    @Override
    public void delVirtualPortMapping(UUID hostId, UUID portId) {
        try {
            hostZkManager.delVirtualPortMapping(hostId, portId);
        } catch (SerializationException | StateAccessException e) {
            throw new RuntimeException("Cannot delete port mapping", e);
        }
    }

    @Override
    public void setHostVersion(UUID hostId) {
        try {
            hostZkManager.setHostVersion(hostId);
        } catch (StateAccessException e) {
            throw new RuntimeException("Cannot set host version", e);
        }
    }

    @Override
    public UUID createChain() {
        try {
            return dataClient.chainsCreate(new Chain());
        } catch (StateAccessException | SerializationException e) {
            throw new RuntimeException("Cannot create chain", e);
        }
    }

    @Override
    public UUID createRule(UUID chainId, short ethertype) {

        try {
            Condition condition = new Condition();
            condition.etherType = (int) ethertype;

            LiteralRule rule = new LiteralRule(condition);
            rule.setAction(RuleResult.Action.ACCEPT);
            rule.setPosition(1);
            rule.setChainId(chainId);
            return dataClient.rulesCreate(rule);
        } catch (Rule.RuleIndexOutOfBoundsException |
                 StateAccessException |
                 SerializationException e) {
            throw new RuntimeException("Cannot create rule", e);
        }
    }
}
