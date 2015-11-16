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
package org.midonet.cluster;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.serialization.SerializationException;

/**
 * This interface defines the contract required to any device ZkManager that
 * wants to expose its set of devices to EntityIdSetMonitor and EntityMonitor.
 *
 * @param <T> the type of device
 */
public interface WatchableZkManager<K, T> {

    /**
     * Retrieve a device and set a watcher on it, if it exists.
     *
     * @return the item data, or null if it does not exist.
     */
    T get(K key, Runnable watcher) throws StateAccessException,
                                          SerializationException;
}

