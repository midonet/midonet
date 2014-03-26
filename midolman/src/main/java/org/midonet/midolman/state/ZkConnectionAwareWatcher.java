/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.midonet.midolman.serialization.SerializationException;

public interface ZkConnectionAwareWatcher extends Watcher {
    ZkConnection getZkConnection();

    void setZkConnection(ZkConnection conn);

    void scheduleOnReconnect(Runnable runnable);

    void scheduleOnDisconnect(Runnable runnable);

    void handleDisconnect(Runnable runnable);

    void handleTimeout(Runnable runnable);

    void handleError(String objectDesc, Runnable retry, KeeperException e);

    void handleError(String objectDesc, Runnable retry, StateAccessException e);
}
