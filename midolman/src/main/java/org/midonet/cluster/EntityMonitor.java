/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.UUID;

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
 * @param <FROM> the type of the entity at storage layer (e.g.: BridgeConfig)
 * @param <TO> the type of the entity to be monitored (e.g.: Bridge)
 */
public class EntityMonitor<FROM, TO> {

    private static final Logger log =
        LoggerFactory.getLogger(EntityMonitor.class);

    /* Access to the underlying data storage */
    private final WatchableZkManager<FROM> zkManager;

    /* Zk Connection watcher */
    private final ZkConnectionAwareWatcher zkConnWatcher;

    /* Event publication streams */
    private final Subject<TO, TO> updateStream = PublishSubject.create();

    private final Transformer<FROM, TO> converter;

    /**
     * An interface to map one type to another.
     */
    public interface Transformer<FROM, TO> {
        TO transform(UUID id, FROM data);
    }

    /**
     * The notification handler for data changes in ZK, it will delegate to the
     * EntityMonitor. We use a TypedWatcher instead of a Runnable so that we can
     * filter out specific event types.
     */
    private class Watcher extends Directory.DefaultTypedWatcher {
        private final EntityMonitor<FROM, TO> mon;
        private final UUID id;

        public Watcher(EntityMonitor<FROM, TO> mon, UUID id) {
            this.mon = mon;
            this.id = id;
        }

        @Override
        public void pathDataChanged(String path) {
            FROM item = mon.getAndWatch(id, this);
            if (item != null)
                mon.notifyUpdate(id, item);
        }

        @Override
        public void run() {
            log.debug("Received a non-data notification, reinstalling");
            mon.getAndWatch(id, this);
        }
    }

    /**
     * Construct a new EntityMonitor.
     *
     * @param zkManager that should be used to access this entity's storage
     * @param zkConnWatcher the zk connection watcher
     * @param converter to translate from the storage model to the data model
     */
    public EntityMonitor(WatchableZkManager<FROM> zkManager,
                         ZookeeperConnectionWatcher zkConnWatcher,
                         Transformer<FROM, TO> converter) {
        this.zkManager = zkManager;
        this.zkConnWatcher = zkConnWatcher;
        this.converter = converter;
    }

    /**
     * Fetches the entity from the storage and sets a new watcher for it. If
     * there are connection problems it will arrange with the connection watcher
     * to retry until the connection is restored.
     *
     * @param id the id of the entity
     * @param watcher the watcher on the entity data
     * @return the entity if it could be retrieved
     */
    private FROM getAndWatch(UUID id, Directory.TypedWatcher watcher) {
        try {
            FROM item = zkManager.get(id, watcher);
            if (item == null)
                log.warn("Trying to set watcher for non existing entity {}",
                         id);
            return item;
        } catch (NoStatePathException e) {
            log.warn("Entity {} doesn't exist in storage", id, e);
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve entity {} from storage", id);
            zkConnWatcher.handleError("EntityMonitor " + id, watcher, e);
        } catch (SerializationException e) {
            log.error("Failed to deserialize entity {}", id, e);
        }
        return null;
    }

    /**
     * Retrieve an entity and start watching it.
     */
    public void watch(UUID id) {
        FROM entity = getAndWatch(id, new Watcher(this, id));
        if (entity != null) {
            notifyUpdate(id, entity);
        }
    }

    /**
     * Process device updates.
     */
    private void notifyUpdate(UUID id, FROM item) {
        updateStream.onNext(converter.transform(id, item));
    }

    /**
     * Get the observable for entity updates.
     */
    public Observable<TO> updated() {
        return updateStream.asObservable();
    }
}

