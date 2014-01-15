/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state.zkManagers;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

public class AdRouteZkManager extends AbstractZkManager {

    public static final class AdRouteConfig {

        public InetAddress nwPrefix;
        public byte prefixLength;
        public UUID bgpId;

        public AdRouteConfig(UUID bgpId, InetAddress nwPrefix, byte prefixLength) {
            this.bgpId = bgpId;
            this.nwPrefix = nwPrefix;
            this.prefixLength = prefixLength;
        }

        // Default constructor for the Jackson deserialization.
        public AdRouteConfig() {
            super();
        }
    }

    /**
     * AdRouteZkManager constructor.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public AdRouteZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    public List<Op> prepareAdRouteCreate(UUID id, AdRouteConfig config)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getAdRoutePath(id),
                serializer.serialize(config),
                        Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        ops.add(Op.create(paths.getBgpAdRoutePath(config.bgpId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareAdRouteDelete(UUID id) throws StateAccessException,
            SerializationException {
        return prepareAdRouteDelete(id, get(id));
    }

    public List<Op> prepareAdRouteDelete(UUID id, AdRouteConfig config) {
        // Delete the advertising route
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(paths.getAdRoutePath(id), -1));
        ops.add(Op.delete(paths.getBgpAdRoutePath(config.bgpId, id), -1));
        return ops;
    }

    public UUID create(AdRouteConfig adRoute) throws StateAccessException,
             SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareAdRouteCreate(id, adRoute));
        return id;
    }

    public AdRouteConfig get(UUID id, Runnable watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getAdRoutePath(id), watcher);
        return serializer.deserialize(data, AdRouteConfig.class);
    }

    public AdRouteConfig get(UUID id) throws StateAccessException,
            SerializationException {
        return this.get(id, null);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getAdRoutePath(id));
    }

    public void getAdRouteAsync(final UUID adRouteId, DirectoryCallback <AdRouteConfig> adRouteDirectoryCallback,
                            final Directory.TypedWatcher watcher) {

        String adRoutePath = paths.getAdRoutePath(adRouteId);

        zk.asyncGet(adRoutePath,
                DirectoryCallbackFactory.transform(
                        adRouteDirectoryCallback,
                        new Functor<byte[], AdRouteConfig>() {
                            @Override
                            public AdRouteConfig apply(byte[] arg0) {
                                try {
                                    return serializer.deserialize(arg0,
                                            AdRouteConfig.class);
                                } catch (SerializationException e) {
                                    log.warn("Could not deserialize AdRoute data");
                                }
                                return null;
                            }
                        }),
                watcher);
    }
    public void getAdRouteListAsync(UUID bgpId,
                                    final DirectoryCallback<Set<UUID>>
                                        adRouteContentsCallback,
                                Directory.TypedWatcher watcher) {
        String adRoutePath = paths.getBgpAdRoutesPath(bgpId);

        zk.asyncGetChildren(
                adRoutePath,
                DirectoryCallbackFactory.transform(
                        adRouteContentsCallback,
                        new Functor<Set<String>, Set<UUID>>() {
                            @Override
                            public Set<UUID> apply(Set<String> arg0) {
                                return CollectionFunctors.map(
                                        arg0, strToUUIDMapper, new HashSet<UUID>());
                            }
                        }
                ), watcher);
    }

    public List<UUID> list(UUID bgpId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> adRouteIds = zk.getChildren(
                paths.getBgpAdRoutesPath(bgpId), watcher);
        for (String adRouteId : adRouteIds) {
            // For now, get each one.
            result.add(UUID.fromString(adRouteId));
        }
        return result;
    }

    public List<UUID> list(UUID bgpId) throws StateAccessException {
        return this.list(bgpId, null);
    }

    public void update(UUID id, AdRouteConfig config)
            throws StateAccessException, SerializationException {
        byte[] data = serializer.serialize(config);
        zk.update(paths.getAdRoutePath(id), data);
    }

    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareAdRouteDelete(id));
    }
}
