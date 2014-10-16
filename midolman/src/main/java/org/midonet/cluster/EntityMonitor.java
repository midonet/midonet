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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * This class monitors individual instances of a given type of entity in its ZK
 * storage, and exposes an rx.Observable with all data changes made to them.
 *
 * @param <KEY> the type of the entity key (e.g.: UUID identifier)
 * @param <FROM> the type of the entity at storage layer (e.g.: BridgeConfig)
 * @param <TO> the type of the entity to be monitored (e.g.: Bridge)
 */
public class EntityMonitor<KEY, FROM, TO> {

    private static final Logger log =
        LoggerFactory.getLogger(EntityMonitor.class);

    /* Access to the underlying data storage */
    private final WatchableZkManager<KEY, FROM> zkManager;

    /* Zk Connection watcher */
    private final ZkConnectionAwareWatcher zkConnWatcher;

    /* Event publication streams */
    private final Subject<TO, TO> updateStream = PublishSubject.create();

    private final Transformer<KEY, FROM, TO> converter;

    /**
     * An interface to map one type to another.
     */
    public interface Transformer<KEY, FROM, TO> {
        TO transform(KEY key, FROM data);
    }

    /**
     * The notification handler for data changes in ZK, it will delegate to the
     * EntityMonitor. We use a TypedWatcher instead of a Runnable so that we can
     * filter out specific event types.
     */
    private class Watcher extends Directory.DefaultTypedWatcher {
        private final EntityMonitor<KEY, FROM, TO> mon;
        private final KEY key;

        public Watcher(EntityMonitor<KEY, FROM, TO> mon, KEY key) {
            this.mon = mon;
            this.key = key;
        }

        @Override
        public void pathDataChanged(String path) {
            FROM item = mon.getAndWatch(key, this);
            if (item != null)
                mon.notifyUpdate(key, item);
        }

        @Override
        public void run() {
            log.debug("Received a non-data notification, reinstalling");
            mon.getAndWatch(key, this);
        }
    }

    /**
     * Construct a new EntityMonitor.
     *
     * @param zkManager that should be used to access this entity's storage
     * @param zkConnWatcher the zk connection watcher
     * @param converter to translate from the storage model to the data model
     */
    public EntityMonitor(WatchableZkManager<KEY, FROM> zkManager,
                         ZookeeperConnectionWatcher zkConnWatcher,
                         Transformer<KEY, FROM, TO> converter) {
        this.zkManager = zkManager;
        this.zkConnWatcher = zkConnWatcher;
        this.converter = converter;
    }

    /**
     * Fetches the entity from the storage and sets a new watcher for it. If
     * there are connection problems it will arrange with the connection watcher
     * to retry until the connection is restored.
     *
     * @param key the key of the entity
     * @param watcher the watcher on the entity data
     * @return the entity if it could be retrieved
     */
    private FROM getAndWatch(KEY key, Directory.TypedWatcher watcher) {
        try {
            FROM item = zkManager.get(key, watcher);
            if (item == null)
                log.warn("Trying to set watcher for non existing entity {}",
                         key);
            return item;
        } catch (NoStatePathException e) {
            log.warn("Entity {} doesn't exist in storage", key);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve entity {} from storage", key);
            zkConnWatcher.handleError("EntityMonitor " + key, watcher, e);
        } catch (SerializationException e) {
            log.error("Failed to deserialize entity {}", key, e);
        }
        return null;
    }

    /**
     * Retrieve an entity and start watching it.
     */
    public void watch(KEY key) {
        FROM entity = getAndWatch(key, new Watcher(this, key));
        if (entity != null) {
            notifyUpdate(key, entity);
        }
    }

    /**
     * Process device updates.
     */
    private void notifyUpdate(KEY key, FROM item) {
        updateStream.onNext(converter.transform(key, item));
    }

    /**
     * Get the observable for entity updates.
     */
    public Observable<TO> updated() {
        return updateStream.asObservable();
    }
}

