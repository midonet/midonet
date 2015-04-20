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

package org.midonet.brain.services.vxgw.monitor;

import rx.Observable;
import rx.functions.Action1;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.data.Entity;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

public abstract class DeviceMonitor<KEY,
                                   TYPE extends Entity.Base<KEY, ?, TYPE>> {

    protected final DataClient midoClient;
    protected final ZookeeperConnectionWatcher zkConnWatcher;

    private final EntityMonitor<KEY, ?, TYPE> entityMonitor;
    private final EntityIdSetMonitor<KEY> entityIdSetMonitor;

    public static class DeviceMonitorException extends Exception {
        public DeviceMonitorException(Throwable cause) {
            super("Failed to create a device monitor.", cause);
        }
    }

    private final Action1<EntityIdSetEvent<KEY>> onUpdate =
        new Action1<EntityIdSetEvent<KEY>>() {
            @Override
            public void call(EntityIdSetEvent<KEY> event) {
                switch(event.type) {
                    case STATE:
                    case CREATE:
                        entityMonitor.watch(event.value);
                    default:
                }
            }
        };

    public DeviceMonitor(DataClient midoClient,
                         ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitorException {

        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;

        entityMonitor = getEntityMonitor();
        try {
            entityIdSetMonitor = getEntityIdSetMonitor();
        } catch (StateAccessException e) {
            throw new DeviceMonitorException(e);
        }

        entityIdSetMonitor.getObservable().subscribe(onUpdate);
        entityIdSetMonitor.notifyState();
    }

    protected abstract EntityMonitor<KEY, ?, TYPE> getEntityMonitor();

    protected abstract EntityIdSetMonitor<KEY> getEntityIdSetMonitor()
        throws StateAccessException;

    /**
     * Instructs the underlying entity ID set monitor to generate a new state
     * notification to update the state for all subscribers.
     */
    public void notifyState() {
        entityIdSetMonitor.notifyState();
    }

    /**
     * Gets the observable for entity additions, deletions and state
     * notifications.
     */
    public Observable<EntityIdSetEvent<KEY>> getEntityIdSetObservable() {
        return entityIdSetMonitor.getObservable();
    }

    /**
     * Gets the observable for entity updates.
     */
    public Observable<TYPE> getEntityObservable() {
        return entityMonitor.updated();
    }
}
