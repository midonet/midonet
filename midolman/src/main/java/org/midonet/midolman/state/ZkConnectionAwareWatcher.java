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
import org.apache.zookeeper.Watcher;

import org.midonet.cluster.backend.zookeeper.ZkConnection;

public interface ZkConnectionAwareWatcher extends Watcher {

    void setZkConnection(ZkConnection conn);

    void scheduleOnReconnect(Runnable runnable);

    void scheduleOnDisconnect(Runnable runnable);

    void handleDisconnect(Runnable runnable);

    void handleTimeout(Runnable runnable);

    void handleError(String objectDesc, Runnable retry, KeeperException e);

    void handleError(String objectDesc, Runnable retry, StateAccessException e);
}
