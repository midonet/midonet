/*
 * Copyright (c) 2012-2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

public class AdRouteZkManager
        extends AbstractZkManager<UUID, AdRouteZkManager.AdRouteConfig> {

    public static final class AdRouteConfig extends BaseConfig {

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

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getAdRoutePath(id);
    }

    @Override
    protected Class getConfigClass() {
        return AdRouteConfig.class;
    }

    public List<Op> prepareCreate(UUID id, AdRouteConfig config)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<Op>();
        ops.add(simpleCreateOp(id, config));
        ops.add(Op.create(paths.getBgpAdRoutePath(config.bgpId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    public List<Op> prepareDelete(UUID id)
            throws StateAccessException, SerializationException {
        return prepareDelete(id, get(id));
    }

    public List<Op> prepareDelete(UUID id, AdRouteConfig config) {
        // Delete the advertising route
        List<Op> ops = new ArrayList<>(2);
        ops.add(Op.delete(paths.getAdRoutePath(id), -1));
        ops.add(Op.delete(paths.getBgpAdRoutePath(config.bgpId, id), -1));
        return ops;
    }

    public UUID create(AdRouteConfig adRoute) throws StateAccessException,
             SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareCreate(id, adRoute));
        return id;
    }

    public void getAdRouteListAsync(UUID bgpId,
                                    final DirectoryCallback<Set<UUID>>
                                        adRouteContentsCallback,
                                Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getBgpAdRoutesPath(bgpId),
                        adRouteContentsCallback, watcher);
    }

    public List<UUID> list(UUID bgpId, Runnable watcher)
            throws StateAccessException {
        return getUuidList(paths.getBgpAdRoutesPath(bgpId), watcher);
    }

    public List<UUID> list(UUID bgpId) throws StateAccessException {
        return this.list(bgpId, null);
    }

    public void update(UUID id, AdRouteConfig config)
            throws StateAccessException, SerializationException {
        zk.multi(Arrays.asList(simpleUpdateOp(id, config)));
    }

    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareDelete(id));
    }
}
