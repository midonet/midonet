/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.zkManagers.BaseConfig;
import org.midonet.midolman.state.zkManagers.ConfigGetter;
import org.midonet.util.functors.Functor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    protected static final Functor<Set<String>, Map<UUID, UUID>>
        splitStrSetToUuidUuidMap =
            new Functor<Set<String>, Map<UUID, UUID>>() {
                @Override
                public Map<UUID, UUID> apply(Set<String> keys) {
                    Map<UUID, UUID> map = new HashMap<UUID, UUID>(keys.size());
                    for (String name : keys) {
                        String[] items = name.split("_");
                        if (items.length < 2) {
                            throw new IllegalArgumentException(
                                    "Invalid input, cannot split " + name);
                        }
                        map.put(UUID.fromString(items[0]),
                                UUID.fromString(items[1]));
                    }
                    return map;
                }
            };

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

    /**
     * Creates an operation for storing the specified CONFIG with the
     * specified KEY. Does not create or otherwise affect any other
     * nodes.
     */
    protected Op simpleCreateOp(K key, CFG config)
            throws SerializationException {
        return Op.create(getConfigPath(key), serializer.serialize(config),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    /**
     * Creates an operation for updating the node for the specified
     * specified KEY with the data of the specified CONFIG. Does
     * not create, update, or otherwise affect any other nodes.
     */
    protected Op simpleUpdateOp(K key, CFG config)
            throws SerializationException {
        return Op.setData(getConfigPath(key), serializer.serialize(config), -1);
    }

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

    /**
     * Gets the config for the specified resource ID asynchronously.
     *
     * @param callback Receives the config when available.
     * @param watcher Optional watcher to be notified of a future update.
     */
    public void getAsync(final K key,
                         DirectoryCallback<CFG> callback,
                         Directory.TypedWatcher watcher) {
        getAsync(getConfigPath(key), getConfigClass(), callback, watcher);
    }
}
