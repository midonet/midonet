/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


/**
 *  Abstract class for the Zookeeper manager classes
 */
public abstract class AbstractZkManager {

    protected final static Logger log =
            LoggerFactory.getLogger(AbstractZkManager.class);

    protected final ZkManager zk;
    protected final PathBuilder paths;
    protected final Serializer serializer;

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
        this.zk = zk;
        this.paths = paths;
        this.serializer = serializer;
    }

    protected Set<UUID> getChildUuids(String path)
            throws StateAccessException {
        Set<String> idStrs = zk.getChildren(path, null);
        Set<UUID> ids = new HashSet<>(idStrs.size());
        for (String idStr : idStrs) {
            try {
                ids.add(UUID.fromString(idStr));
            } catch (IllegalArgumentException ex) {
                // Nothing we can do but log an error and move on.
                log.error("'{}' at path '{}' is not a valid UUID. Zookeeper" +
                          "data may be corrupted.",
                          new Object[]{idStr, path, ex});
            }
        }
        return ids;
    }

    protected <T> void getAsync(String path, final Class<T> clazz,
                                DirectoryCallback<T> callback,
                                Directory.TypedWatcher watcher) {
        zk.asyncGet(
                path,
                DirectoryCallbackFactory.transform(
                        callback,
                        new Functor<byte[], T>() {
                            @Override
                            public T apply(byte[] arg0) {
                                try {
                                    return serializer.deserialize(arg0, clazz);
                                } catch (SerializationException e) {
                                    log.warn("Could not deserialize " +
                                             clazz.getSimpleName() + " data");
                                    return null;
                                }
                            }
                        }),
                watcher);
    }

    protected void getUUIDSetAsync(String path,
                                   DirectoryCallback<Set<UUID>> callback,
                                   Directory.TypedWatcher watcher) {
        zk.asyncGetChildren(
                path,
                DirectoryCallbackFactory.transform(
                        callback, CollectionFunctors.strSetToUUIDSet),
                watcher);
    }
}
