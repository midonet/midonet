/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.Set;
import java.util.UUID;

import com.google.common.util.concurrent.ValueFuture;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

public class PortSetZkManager extends BaseZkManager {

    private static final Logger log = LoggerFactory
        .getLogger(PortSetZkManager.class);


    /**
     * Initializes a PortGroupZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public PortSetZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    public void getPortSetAsync(UUID portSetId,
                                final DirectoryCallback<Set<UUID>>
                                        portSetContentsCallback,
                                Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getPortSetPath(portSetId),
                portSetContentsCallback, watcher);
    }

    public Set<UUID> getPortSet(UUID portSetId, Directory.TypedWatcher watcher)
            throws StateAccessException {
        return getUuidSet(paths.getPortSetPath(portSetId));
    }

    public void addMemberAsync(UUID portSetId, UUID memberId,
                               DirectoryCallback.Add cb) {

        String portSetPath =
            paths.getPortSetEntryPath(portSetId, memberId);

        zk.asyncAdd(portSetPath, null, CreateMode.EPHEMERAL, cb);
    }

    public void addMember(UUID portSetId, UUID memberId)
        throws StateAccessException {

        String memberEntryPath =
            paths.getPortSetEntryPath(portSetId, memberId);
        zk.add(memberEntryPath, null, CreateMode.EPHEMERAL);
    }

    public void delMemberAsync(UUID portSetId, UUID entryId,
                               DirectoryCallback.Void callback) {
        String portSetPath = paths.getPortSetEntryPath(portSetId, entryId);
        zk.asyncDelete(portSetPath, callback);
    }

    public void delMember(UUID portSetId, UUID memberID)
        throws StateAccessException {
        String portSetPath =
            paths.getPortSetEntryPath(portSetId, memberID);
        zk.delete(portSetPath);
    }

    private <T> DirectoryCallback<T> makeCallback(final ValueFuture<T> valueFuture) {
        return new DirectoryCallback<T>() {
            @Override
            public void onSuccess(Result<T> data) {
                valueFuture.set(data.getData());
            }

            @Override
            public void onTimeout() {
                valueFuture.cancel(true);
            }

            @Override
            public void onError(KeeperException e) {
                valueFuture.setException(e);
            }
        };
    }
}
