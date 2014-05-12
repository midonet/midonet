/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import java.util.*;
import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;


public class NetworkZkManager extends BaseZkManager {

    private final BridgeZkManager bridgeZkManager;

    @Inject
    public NetworkZkManager(ZkManager zk,
                            PathBuilder paths,
                            Serializer serializer,
                            BridgeZkManager bridgeZkManager) {
        super(zk, paths, serializer);
        this.bridgeZkManager = bridgeZkManager;
    }

    public void prepareCreateNetwork(List<Op> ops, Network network)
            throws SerializationException, StateAccessException {

        String path = paths.getNeutronNetworkPath(network.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(network)));

        BridgeConfig config = ConfigFactory.createBridge(network);
        ops.addAll(bridgeZkManager.prepareBridgeCreate(network.id, config));
    }

    public void prepareDeleteNetwork(List<Op> ops, UUID id)
            throws SerializationException, StateAccessException {

        Network network = getNetwork(id);
        if (network == null) {
            return;
        }

        ops.addAll(bridgeZkManager.prepareBridgeDelete(id));

        String path = paths.getNeutronNetworkPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareUpdateNetwork(List<Op> ops, Network network)
            throws SerializationException, StateAccessException,
            BridgeZkManager.VxLanPortIdUpdateException {

        UUID id = network.id;

        BridgeZkManager.BridgeConfig config = ConfigFactory.createBridge(
                network);
        ops.addAll(bridgeZkManager.prepareUpdate(id, config, true));

        String path = paths.getNeutronNetworkPath(id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(network)));
    }

    public Network getNetwork(UUID networkId)
            throws StateAccessException, SerializationException {

        String path = paths.getNeutronNetworkPath(networkId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), Network.class);
    }

    public List<Network> getNetworks()
            throws StateAccessException, SerializationException {

        String path= paths.getNeutronNetworksPath();
        Set<String> networkIds = zk.getChildren(path);

        List<Network> networks = new ArrayList<>();
        for (String networkId : networkIds) {
            networks.add(getNetwork(UUID.fromString(networkId)));
        }

        return networks;
    }
}
