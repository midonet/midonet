/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.state;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;

public interface ZkConnectionAwareWatcher extends Watcher {
    void setZkConnection(ZkConnection conn);

    void scheduleOnReconnect(Runnable runnable);

    void handleDisconnect(Runnable runnable);

    void handleTimeout(Runnable runnable);

    void handleError(String objectDesc, Runnable retry, KeeperException e);

    void handleError(String objectDesc, Runnable retry, StateAccessException e);
}
