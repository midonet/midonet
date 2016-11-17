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

package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.IPv6Subnet;


public class BridgeDhcpV6ZkManager extends BaseZkManager {

    public static class Subnet6 {
        IPv6Subnet prefix;

        /* Default constructor for deserialization. */
        public Subnet6() {
        }

        public IPv6Subnet getPrefix() {
            return prefix;
        }

        public void setPrefix(IPv6Subnet prefix) {
            this.prefix = prefix;
        }

        @Override
        public String toString() {
            return "Subnet6{" +
                "prefix=" + prefix.toString() +
                '}';
        }
    }

    public static class Host {
        String clientId;
        IPv6Addr fixedAddress;
        String name;

        /* Default constructor for deserialization. */
        public Host() {
        }

        public Host(String clientId, IPv6Addr fixedAddress, String name) {
            this.clientId = clientId;
            this.fixedAddress = fixedAddress;
            this.name = name;
        }

        public String getClientId() {
            return clientId;
        }

        public IPv6Addr getFixedAddress() {
            return fixedAddress;
        }

        public String getName() {
            return name;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public void setFixedAddress(IPv6Addr fixedAddress) {
            this.fixedAddress = fixedAddress;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * Initializes a BridgeDhcpV6ZkManager object with a ZooKeeper client
     * and the root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    @Inject
    public BridgeDhcpV6ZkManager(ZkManager zk, PathBuilder paths,
                                 Serializer serializer) {
        super(zk, paths, serializer);
    }

    public Subnet6 getSubnet6(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getBridgeDhcpSubnet6Path(bridgeId,
                prefix), null);
        return serializer.deserialize(data, Subnet6.class);
    }

    public boolean existsSubnet6(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException {
        return zk.exists(paths.getBridgeDhcpSubnet6Path(bridgeId,
                prefix));
    }

    public List<IPv6Subnet> listSubnet6s(UUID bridgeId)
            throws StateAccessException {
        Set<String> prefixStrings = zk.getChildren(
                paths.getBridgeDhcpV6Path(bridgeId), null);
        List<IPv6Subnet> prefixes = new ArrayList<>();
        for (String prefixStr : prefixStrings)
            prefixes.add(IPv6Subnet.fromCidr(prefixStr));
        return prefixes;
    }

    public Host getHost(UUID bridgeId, IPv6Subnet prefix, String clientId)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getBridgeDhcpV6HostPath(
                bridgeId, prefix, clientId), null);
        return serializer.deserialize(data, Host.class);
    }

    public List<Host> getHosts(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException {
        Set<String> clientIds = zk.getChildren(
                paths.getBridgeDhcpV6HostsPath(bridgeId, prefix));
        List<Host> hosts = new ArrayList<>();
        for (String clientId : clientIds)
            hosts.add(getHost(bridgeId, prefix, clientId));
        return hosts;
    }
}
