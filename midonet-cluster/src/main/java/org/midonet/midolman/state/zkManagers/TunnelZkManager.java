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

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;


/**
 * Class to manage the Tunnel ZooKeeper data.
 */
public class TunnelZkManager
        extends AbstractZkManager<Integer, TunnelZkManager.TunnelKey> {
    private final static Logger log =
         LoggerFactory.getLogger(TunnelZkManager.class);

    public static class TunnelKey {

        public TunnelKey() {
            super();
        }

        public TunnelKey(UUID ownerId) {
            super();
            this.ownerId = ownerId;
        }

        public UUID ownerId;
    }

    /**
     * Initializes a TunnelZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    @Inject
    public TunnelZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(Integer id) {
        return paths.getTunnelPath(id);
    }

    @Override
    protected Class<TunnelKey> getConfigClass() {
        return TunnelKey.class;
    }

    private int extractTunnelKeyFromPath(String path) {
        int idx = path.lastIndexOf('/');
        return Integer.parseInt(path.substring(idx + 1));
    }

    /**
     * Constructs a list of operations to perform in a tunnelKey update.
     *
     * @param tunnelKey
     *            TunnelKey ZooKeeper entry to update.
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareTunnelUpdate(int tunnelKeyId, TunnelKey tunnelKey)
            throws StateAccessException, SerializationException {
        return Arrays.asList(simpleUpdateOp(tunnelKeyId, tunnelKey));
    }

    /***
     * Constructs a list of operations to perform in a tunnel deletion.
     *
     * @param tunnelKeyId
     *            ZK entry of the tunnel to delete.
     */
    public List<Op> prepareTunnelDelete(int tunnelKeyId) {
        String path = paths.getTunnelPath(tunnelKeyId);
        log.debug("Preparing to delete: " + path);
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(path, -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new Tunnel key.
     *
     * @return The ID of the newly created Tunnel.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public int createTunnelKeyId()
            throws StateAccessException {
        String path = zk.addPersistentSequential(paths.getTunnelPath(), null);
        int key = extractTunnelKeyFromPath(path);
        // We don't use Zero as a valid Tunnel key because our PacketIn method
        // uses that value to denote "The Tunnel ID wasn't set".
        if (0 == key) {
            path = zk.addPersistentSequential(paths.getTunnelPath(), null);
            key = extractTunnelKeyFromPath(path);
        }
        return key;
    }

}
