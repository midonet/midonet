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

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.ConfigWithProperties;

/**
 * ZooKeeper DAO class for Chains.
 */
public class ChainZkManager
        extends AbstractZkManager<UUID, ChainZkManager.ChainConfig> {

    public static class ChainConfig extends ConfigWithProperties {
        // The chain name should only be used for logging.
        public final String name;
        public ChainConfig() {
            this.name = "";
        }
    }

    /**
     * Constructor to set ZooKeeper and base path.
     */
    public ChainZkManager(ZkManager zk, PathBuilder paths,
                          Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getChainPath(id);
    }

    @Override
    protected Class<ChainConfig> getConfigClass() {
        return ChainConfig.class;
    }

    /**
     * Checks whether a chain with the given ID exists.
     *
     * @param id Chain ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getChainPath(id));
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a chain with the given ID.
     *
     * @param id The ID of the chain.
     * @return ChainConfig object found.
     * @throws StateAccessException
     */
    public ChainConfig get(UUID id) throws StateAccessException,
            SerializationException {
        byte[] data = zk.get(paths.getChainPath(id), null);
        return serializer.deserialize(data, ChainConfig.class);
    }

}
