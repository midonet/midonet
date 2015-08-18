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

import java.util.UUID;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;


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
}
