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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.apache.zookeeper.Watcher;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.StateVersionException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkPathManager;
import org.midonet.packets.IPv4Addr;

import static java.util.Arrays.asList;

public class VtepZkManager
        extends AbstractZkManager<IPv4Addr, VtepZkManager.VtepConfig>
        implements WatchableZkManager<IPv4Addr, VtepZkManager.VtepConfig> {

    public static final int MIN_VNI = 10000;
    public static final int MAX_VNI = 0xffffff;

    public static class VtepConfig {
        public int mgmtPort;
        public UUID tunnelZone;
    }

    public VtepZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(IPv4Addr key) {
        return paths.getVtepPath(key);
    }

    @Override
    protected Class<VtepConfig> getConfigClass() {
        return VtepConfig.class;
    }

    public List<Op> prepareCreate(IPv4Addr ipAddr, VtepConfig vtepConfig)
            throws SerializationException {
        return asList(simpleCreateOp(ipAddr, vtepConfig),
                      zk.getPersistentCreateOp(
                              paths.getVtepBindingsPath(ipAddr), null));
    }

    /**
     * Removes the ownership from a specific VxLanGatewayService.
     */
    public List<Op> prepareDeleteOwner(IPv4Addr ipAddr) {
        return asList(Op.delete(paths.getVtepOwnerPath(ipAddr), -1));
    }

    public List<Op> prepareDelete(IPv4Addr ipAddr) {
        return asList(Op.delete(paths.getVtepBindingsPath(ipAddr), -1),
                      Op.delete(paths.getVtepPath(ipAddr), -1));
    }

    public List<Op> prepareUpdate(IPv4Addr ipAddr, VtepConfig vtepConfig)
        throws SerializationException {
        return asList(simpleUpdateOp(ipAddr, vtepConfig));
    }

    public List<Op> prepareCreateBinding(IPv4Addr ipAddr, String portName,
                                         short vlanId, UUID networkId)
            throws StateAccessException {
        return asList(zk.getPersistentCreateOp(
            paths.getVtepBindingPath(ipAddr, portName, vlanId, networkId),
            null));
    }

    public List<Op> prepareDeleteBinding(IPv4Addr ipAddr, String portName,
                                         short vlanId)
            throws StateAccessException {
        for (VtepBinding binding : getBindings(ipAddr)) {
            if (vlanId == binding.getVlanId() &&
                    portName.equals(binding.getPortName()))
                return asList(zk.getDeleteOp(paths.getVtepBindingPath(
                        ipAddr, portName, vlanId, binding.getNetworkId())));
        }

        return asList();
    }

    public List<Op> prepareDeleteAllBindings(IPv4Addr ipAddr, UUID bridgeId)
            throws StateAccessException {
        // TODO: Ick. Maybe we should index bindings by bridge.
        List<VtepBinding> bindings = getBindings(ipAddr);
        List<Op> ops = new ArrayList<>();
        for (VtepBinding binding : bindings) {
            if (bridgeId == null || bridgeId.equals(binding.getNetworkId())) {
                ops.add(zk.getDeleteOp(paths.getVtepBindingPath(
                        ipAddr, binding.getPortName(),
                        binding.getVlanId(), binding.getNetworkId())));
            }
        }

        return ops;
    }

    public VtepBinding getBinding(IPv4Addr ipAddr, String portName,
                                  short vlanId) throws StateAccessException {
        List<VtepBinding> bindings = getBindings(ipAddr);
        for (VtepBinding binding : bindings) {
            if (vlanId == binding.getVlanId() &&
                portName.equals(binding.getPortName())) {
                return binding;
            }
        }

        return null;
    }

    public List<VtepBinding> getBindings(IPv4Addr ipAddr)
            throws StateAccessException {
        String bindingsPath = paths.getVtepBindingsPath(ipAddr);
        Set<String> children = zk.getChildren(bindingsPath);
        List<VtepBinding> bindings = new ArrayList<>(children.size());
        for (String child : children) {
            String[] parts = child.split("_", 3);
            if (parts.length != 3) {
                throw new IllegalStateException(
                        "Invalid binding key: " + child, null);
            }

            short vlanId = Short.parseShort(parts[0]);
            UUID networkId = UUID.fromString(parts[1]);
            String portName = ZkPathManager.decodePathSegment(parts[2]);
            bindings.add(new VtepBinding(portName, vlanId, networkId));
        }

        return bindings;
    }

    /**
     * Tries to take ownership of a VTEP for the given node identifier.
     *
     * @param ip The management IP of the VTEP
     * @param ownerId The owner identifier.
     * @return The identifier of the current owner, never null.
     */
    public UUID tryOwnVtep(IPv4Addr ip, UUID ownerId)
        throws StateAccessException, SerializationException {
        return tryOwnVtep(ip, ownerId, null);
    }

    /**
     * Tries to take ownership of a VTEP for the given owner identifier. If the
     * watcher is not null, the method installs an exists watch on the ownership
     * node, after modifying the current owner.
     *
     * @param ip The management IP of the VTEP
     * @param ownerId The owner identifier.
     * @param watcher The watcher, can be null.
     * @return The identifier of the current owner, never null.
     */
    public UUID tryOwnVtep(IPv4Addr ip, UUID ownerId, Watcher watcher)
        throws StateAccessException, SerializationException {

        Integer version = -1;
        byte[] writeData = serializer.serialize(ownerId.toString());
        UUID owner = null;

        // Compute the owner node path.
        String path = paths.getVtepOwnerPath(ip);

        do {
            log.debug("Owner {} trying to own VTEP {}", ownerId, ip);

            // Try get the current owner.
            try {
                Map.Entry<byte[], Integer> result =
                    zk.getWithVersion(path, null);

                owner = UUID.fromString(
                    serializer.deserialize(result.getKey(), String.class));
                version = result.getValue();
                log.debug("Current owner for VTEP {} is {}", ip, owner);
            } catch (NoStatePathException e) {
                log.debug("No owner for VTEP {}, taking ownership.", ip);
            } catch (IllegalArgumentException e) {
                throw new SerializationException(
                    "Corrupt owner identifier for VTEP", e);
            }

            if (null == owner) try {
                // No owner: try take ownership and set owner to current.
                zk.addEphemeral(path, writeData);
                owner = ownerId;
            } catch (StatePathExistsException e) {
                // If a different owner took ownership, retry.
                log.debug("Unexpected different owner for VTEP {} (retrying)",
                          ip);
            } else if (owner.equals(ownerId)) try {
                // The current node is the owner: delete and recreate node.
                zk.multi(Arrays.asList(
                    Op.delete(path, version),
                    zk.getEphemeralCreateOp(path, writeData)
                ));
            } catch (NoStatePathException | StatePathExistsException |
                StateVersionException e) {
                log.debug("Failed creating the ownership node for VTEP {} "
                          + "(retrying)", ip);
                owner = null;
            } else {
                log.debug("Previous owner for VTEP {} with identifier {}",
                          ip, owner);
            }
        } while (null == owner);

        // Install an exists watch on the owner path.
        if (null != watcher) {
            zk.exists(path, watcher);
        }

        return owner;
    }

    public int getNewVni() throws StateAccessException {
        for (int i = 0; i < 10; i++) {
            // Get the VNI counter node and its version.
            String path = paths.getVniCounterPath();
            Map.Entry<byte[], Integer> entry = zk.getWithVersion(path, null);
            int vni = Integer.parseInt(new String(entry.getKey()));
            int nodeVersion = entry.getValue();

            // Try to increment the counter node.
            try {
                int newVni = (vni < MAX_VNI) ? vni + 1 : MIN_VNI;
                byte[] newData = Integer.toString(newVni).getBytes();
                zk.update(path, newData, nodeVersion);
                return vni;
            } catch (StateVersionException ex) {
                log.warn("getNewVni() failed due to concurrent update. " +
                         "Trying again.");
            }
        }

        // Time to buy some lottery tickets!
        throw new RuntimeException("getNewVni() failed due to concurrent " +
                                   "updates ten times in a row.");
    }

    @Override
    public List<IPv4Addr> getAndWatchIdList(Runnable watcher)
        throws StateAccessException {

        Set<String> vtepIpStrs = zk.getChildren(paths.getVtepsPath(), watcher);
        List<IPv4Addr> vtepIps = new ArrayList<>(vtepIpStrs.size());
        for (String vtepIpStr : vtepIpStrs) {
            try {
                vtepIps.add(IPv4Addr.fromString(vtepIpStr));
            } catch (IllegalArgumentException ex) {
                log.error("'{}' at path '{}' is not a valid IPv4 address. "
                          + "Zookeeper data may be corrupt.",
                          vtepIpStr, paths.getVtepsPath(), ex);
            }
        }
        return vtepIps;
    }
}
