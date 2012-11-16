/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.state;

import org.apache.zookeeper.Watcher;

public interface ZkConnectionAwareWatcher extends Watcher {
    void setZkConnection(ZkConnection conn);

    void scheduleOnReconnect(Runnable runnable);
}
