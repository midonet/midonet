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

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.backend.zookeeper.ZkConnection;
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher;

public class MockZookeeperConnectionWatcher implements
                                            ZkConnectionAwareWatcher {

    @Override
    public void setZkConnection(ZkConnection conn) { }

    @Override
    public synchronized void process(WatchedEvent event) { }

    @Override
    public void scheduleOnReconnect(Runnable runnable) { }

    @Override
    public void scheduleOnDisconnect(Runnable runnable) { }

    @Override
    public void handleError(String operationDesc, Runnable retry, KeeperException e) {}

    @Override
    public void handleError(String operationDesc, Runnable retry, StateAccessException e) {}

    @Override
    public void handleTimeout(Runnable runnable) {}

    @Override
    public void handleDisconnect(Runnable runnable) {}
}
