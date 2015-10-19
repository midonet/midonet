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
package org.midonet.midolman.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.zkManagers.ConfigGetter;
import org.midonet.nsdb.BaseConfig;

/**
 *  Extends BaseZkManager with common key-value operations needed by
 *  the various ZkManager classes.
 *
 *  @param <K>
 *      The key used to parameterize the Zookeeper path of the
 *      deriving class's primary resource type. Usually UUID.
 *
 *  @param <CFG>
 *      The Zookeeper storage type of the deriving class's primary
 *      resource type, e.g. BridgeZkManager.BridgeConfig. If CFG
 *      extends BaseConfig, then get() will set its id property.
 */
public abstract class AbstractZkManager<K, CFG>
        extends BaseZkManager implements ConfigGetter<K, CFG> {

    protected final static Logger log =
            LoggerFactory.getLogger(AbstractZkManager.class);

    /**
     * Constructor.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public AbstractZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    /**
     * Hook for derived classes to indicate the path where a CONFIG
     * with the specified key is stored.
     */
    protected abstract String getConfigPath(K key);

    /**
     * Hook for derived classes to provide the CONFIG class's class
     * object for deserialization.
     */
    protected abstract Class<CFG> getConfigClass();

    public boolean exists(K key) throws StateAccessException {
        return zk.exists(getConfigPath(key));
    }

    /**
     * Gets the config for the specified resource ID.
     */
    public CFG get(K key)
            throws StateAccessException, SerializationException {
        return get(key, null);
    }

    /**
     * Gets the config for the specified resource ID but does not thrown a
     * NoStatePathException if the resource does not exist.  Instead, returns
     * null if the resource does not exist.
     */
    public CFG tryGet(K key)
        throws StateAccessException, SerializationException {
        try {
            return get(key, null);
        } catch (NoStatePathException ex) {
            return null;
        }
    }

    /**
     * Gets the config for the specified resource ID and sets a watcher.
     */
    public CFG get(K key, Runnable watcher)
            throws StateAccessException, SerializationException {

        byte[] data = zk.get(getConfigPath(key), watcher);
        if (data == null)
            return null;

        CFG config = serializer.deserialize(data, getConfigClass());
        if (config instanceof BaseConfig && key instanceof UUID) {
            ((BaseConfig)config).id = (UUID)key;
        }

        return config;
    }

    /**
     * Gets the configs for the specified multiple resource IDs.
     *
     * @param keys IDs of resources to be retrieved.
     * @return A list of the config of requested resources
     * @throws StateAccessException
     * @throws SerializationException
     */
    public List<CFG> get(Collection<K> keys)
            throws StateAccessException, SerializationException {
        List<CFG> configs = new ArrayList<>(keys.size());
        for (K key : keys) {
            configs.add(get(key));
        }
        return configs;
    }

}
