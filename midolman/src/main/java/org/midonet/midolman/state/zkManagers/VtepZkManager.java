/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StateVersionException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkPathManager;
import org.midonet.packets.IPv4Addr;

import static java.util.Arrays.asList;

public class VtepZkManager
        extends AbstractZkManager<IPv4Addr, VtepZkManager.VtepConfig> {

    public static final int MIN_VNI = 10000;
    public static final int MAX_VNI = 0xff_ffff;

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

    public List<Op> prepareDelete(IPv4Addr ipAddr) {
        return asList(Op.delete(paths.getVtepBindingsPath(ipAddr), -1),
                      Op.delete(paths.getVtepPath(ipAddr), -1));
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
                                  short vlanId)
            throws StateAccessException {
        List<VtepBinding> bindings = getBindings(ipAddr);
        for (VtepBinding binding : bindings) {
            if (vlanId == binding.getVlanId() &&
                    portName.equals(binding.getPortName()))
                return binding;
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
}
