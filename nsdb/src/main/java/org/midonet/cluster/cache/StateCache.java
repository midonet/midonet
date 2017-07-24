/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import scala.Function0;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.AbstractService;
import com.google.protobuf.Message;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.subjects.PublishSubject;

import org.midonet.cluster.data.storage.KeyType;
import org.midonet.cluster.data.storage.metrics.StorageMetrics;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.NodeObservable;
import org.midonet.cluster.util.PathDirectoryObservable;
import org.midonet.cluster.cache.state.HostStateConverter;
import org.midonet.cluster.cache.state.HostStateOwnership;
import org.midonet.cluster.cache.state.PortStateConverter;
import org.midonet.cluster.cache.state.PortStateOwnership;
import org.midonet.util.reactivex.SubscriptionList;

/**
 * Implements a cache for the Network State Database (NSDB) object state. Upon
 * start, an instance of this class exposes an {@link Observable} that when
 * subscribed to emits notifications with the current NSDB object state.
 */
public final class StateCache extends AbstractService {

    private static final Logger LOG = LoggerFactory.getLogger(StateCache.class);

    private static final Map<Class<?>, List<StateKey>> KEYS;
    private static final Observable<Object> NULL_OBSERVABLE =
        Observable.just(null);
    private static final byte[] EMPTY_SINGLE_STATE = new byte[0];
    private static final String[] EMPTY_MULTI_STATE = new String[0];

    static {
        Map<Class<?>, List<StateKey>> keys = new HashMap<>();

        registerKey(keys, Topology.Host.class,
                    new StateKey(KeyType.SingleLastWriteWins(), "alive",
                                 HostStateOwnership.class));
        registerKey(keys, Topology.Host.class,
                    new StateKey(KeyType.SingleLastWriteWins(), "host",
                                 HostStateOwnership.class,
                                 HostStateConverter.class));
        registerKey(keys, Topology.Port.class,
                    new StateKey(KeyType.SingleLastWriteWins(), "active",
                                 PortStateOwnership.class,
                                 PortStateConverter.class));
        registerKey(keys, Topology.Port.class,
                    new StateKey(KeyType.Multiple(), "routes",
                                 PortStateOwnership.class));
        registerKey(keys, Topology.Port.class,
                    new StateKey(KeyType.SingleLastWriteWins(), "bgp",
                                 PortStateOwnership.class));

        KEYS = Collections.unmodifiableMap(keys);
    }

    /**
     * Registers a new state key.
     */
    private static void registerKey(Map<Class<?>, List<StateKey>> keys,
                                    Class<?> clazz, StateKey key) {
        // Register a new key only if it has a non-null ownership, otherwise
        // ignore (it should have already logged an error).
        if (key.ownership() != null) {
            List<StateKey> list = keys.get(clazz);
            if (list == null) {
                list = new ArrayList<>(16);
                keys.put(clazz, list);
            }
            list.add(key);
        }
    }

    /**
     * A cache for an object class: it maintains the list of all current
     * objects in that class.
     */
    private final class ClassCache implements AutoCloseable {

        private final Class<?> clazz;
        private final List<StateKey> keys;

        private final Map<UUID, ObjCache> objects;

        ClassCache(Class<?> clazz, List<StateKey> keys) {
            this.clazz = clazz;
            this.keys = keys;
            this.objects = new HashMap<>();
        }

        /**
         * Starts monitoring the state for the specified object. If the
         * state for the object is already being monitored, then this method
         * does not have any effect.
         * @param id The object identifier.
         * @param message The object protobuf message.
         */
        synchronized void add(UUID id, Message message) {
            ObjCache cache = objects.get(id);
            if (cache == null) {
                LOG.debug("Start monitoring state for object {}:{}",
                          clazz.getSimpleName(), id);
                cache = new ObjCache(clazz, id, keys);
                // Do not add the cache to the map if already closed.
                objects.put(id, cache);
            }

            cache.updateOwner(message);
        }

        /**
         * Stops monitoring the state for the specified object. If the state
         * for the object is not being monitored, then this method does not
         * have any effect.
         * @param id The object identifier.
         */
        synchronized void remove(UUID id) {
            ObjCache cache = objects.remove(id);
            if (cache != null) {
                LOG.debug("Stop monitoring state for object {}:{}",
                          clazz.getSimpleName(), id);
                cache.close();
            }
        }

        /**
         * Adds a snapshot of the current data to the given notification.
         */
        synchronized void snapshot(StateNotification.Snapshot snapshot) {
            for (Map.Entry<UUID, ObjCache> entry : objects.entrySet()) {
                entry.getValue().snapshot(snapshot);
            }
        }

        /**
         * Stops caching state for the current class.
         */
        @Override
        public synchronized void close() {
            LOG.debug("Class {} state cache closed", clazz.getSimpleName());
            for (Map.Entry<UUID, ObjCache> entry : objects.entrySet()) {
                entry.getValue().close();
            }
        }
    }

    /**
     * Monitors the state for a given object.
     */
    private final class ObjCache implements AutoCloseable {

        private final Class<?> clazz;
        private final UUID id;

        private final Map<String, KeyCache> keys;

        ObjCache(Class<?> clazz, UUID id, List<StateKey> keys) {
            this.clazz = clazz;
            this.id = id;
            this.keys = new HashMap<>(keys.size());
            for (StateKey key : keys) {
                add(key);
            }
        }

        /**
         * Updates the owner for the given object.
         */
        synchronized void updateOwner(Message message) {
            for (Map.Entry<String, KeyCache> entry : keys.entrySet()) {
                entry.getValue().updateOwner(message);
            }
        }

        /**
         * Adds a snapshot of the current data to the given notification.
         */
        synchronized void snapshot(StateNotification.Snapshot snapshot) {
            for (Map.Entry<String, KeyCache> entry : keys.entrySet()) {
                snapshot.add(entry.getValue());
            }
        }

        /**
         * Adds a new state key to the current object.
         */
        private synchronized void add(StateKey key) {
            KeyCache cache;
            if (key.type() == KeyType.SingleLastWriteWins()) {
                cache = new SingleKeyCache(clazz, id, key);
            } else {
                cache = new MultiKeyCache(clazz, id, key);
            }
            if (!cache.isClosed())
                keys.put(key.name(), cache);
        }

        /**
         * Stops caching state for the current object.
         */
        @Override
        public synchronized void close() {
            for (Map.Entry<String, KeyCache> entry : keys.entrySet()) {
                entry.getValue().close();
            }
        }
    }

    /**
     * Provides common functionality for monitoring the state keys for a given
     * object.
     */
    private abstract class KeyCache
        implements AutoCloseable, StateNotification.Update {

        protected final Class<?> clazz;
        protected final UUID id;
        protected final StateKey key;

        private final PublishSubject<UUID> ownerSubject;
        private final Observable<UUID> ownerObservable;

        private volatile UUID owner;
        private volatile byte[] singleData = EMPTY_SINGLE_STATE;
        private volatile String[] multiData = EMPTY_MULTI_STATE;
        private volatile boolean closed = false;

        private final Subscriber<Object> subscriber = new Subscriber<Object>() {
            @Override
            public void onCompleted() {
                LOG.debug("Object state {}:{}:{} deleted", clazz.getSimpleName(),
                          id, key.name());
                singleData = EMPTY_SINGLE_STATE;
                multiData = EMPTY_MULTI_STATE;
                notifyUpdate(KeyCache.this);
                close();
            }

            @Override
            public void onError(Throwable throwable) {
                LOG.info("Error for object state {}:{}:{}", clazz.getSimpleName(),
                         id, key.name());
                close();
            }

            @Override
            public void onNext(Object o) {
                // Convert the data for the current state key.
                if (o == null) {
                    // If the object is null, return an empty state.
                    singleData = EMPTY_SINGLE_STATE;
                    multiData = EMPTY_MULTI_STATE;
                } else if (o instanceof ChildData) {
                    // If the object is a ChildData (single value key), fetch
                    // the state from the entry data.
                    ChildData childData = (ChildData) o;
                    if (childData.getData() == null) {
                        singleData = EMPTY_SINGLE_STATE;
                    } else {
                        singleData = key.converter().singleValue(childData.getData());
                    }
                } else if (o instanceof scala.collection.immutable.Set) {
                    // If the object is a Set of strings (multi value key),
                    // fetch the data from the set.
                    scala.collection.immutable.Set<String> set =
                        (scala.collection.immutable.Set<String>) o;
                    multiData = new String[set.size()];
                    scala.collection.Iterator<String> iterator = set.iterator();
                    for (int index = 0; index < multiData.length && iterator.hasNext();
                         index++) {
                        multiData[index] = iterator.next();
                    }
                    multiData = key.converter().multiValue(multiData);
                } else {
                    // Otherwise, unknown data, use empty state.
                    if (key.type().isSingle()) {
                        singleData = EMPTY_SINGLE_STATE;
                    } else {
                        multiData = EMPTY_MULTI_STATE;
                    }
                }

                LOG.debug("Object state {}:{}:{} with owner {} updated",
                          clazz.getSimpleName(), id, key.name(), owner);

                notifyUpdate(KeyCache.this);
            }
        };

        KeyCache(Class<?> clazz, UUID id, StateKey key) {
            this.clazz = clazz;
            this.id = id;
            this.key = key;
            this.ownerSubject = PublishSubject.create();
            this.ownerObservable = ownerSubject.distinctUntilChanged();

            Observable.switchOnNext(ownerObservable.map(this::keyObservable))
                      .subscribe(subscriber);
        }

        @Override
        public Class<?> objectClass() {
            return clazz;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public UUID owner() {
            return owner;
        }

        @Override
        public String key() {
            return key.name();
        }

        @Override
        public KeyType.KeyTypeVal type() {
            return key.type();
        }

        @Override
        public byte[] singleData() {
            return singleData;
        }

        @Override
        public String[] multiData() {
            return multiData;
        }

        /**
         * Closes the current state key cache and notifies all subscribers
         * that the cache state is empty.
         */
        @Override
        public void close() {
            LOG.debug("State key {}:{}:{} cache closed", clazz.getSimpleName(),
                      id, key.name());
            closed = true;
            owner = null;
            singleData = EMPTY_SINGLE_STATE;
            multiData = EMPTY_MULTI_STATE;
            subscriber.unsubscribe();

            // Notify an empty state.
            notifyUpdate(this);
        }

        /**
         * @return True if the cache is closed.
         */
        boolean isClosed() {
            return closed;
        }

        /**
         * Updates the owner for this state key due to an update to the
         * object data. This publishes on the owner subject the new owner for
         * the state of this object due to the object update.
         * @param message The object's protobuf message.
         */
        void updateOwner(Message message) {
            if (message == null) {
                owner = null;
            } else {
                owner = key.ownership().ownerOf(id, message);
            }
            ownerSubject.onNext(owner);
        }

        /**
         * Returns the key observable for the given owner.
         */
        protected abstract Observable<?> keyObservable(UUID owner);

        /**
         * Calls the close handler for this key cache.
         */
        void closeInternal() {
            LOG.trace("State key observable closed");
        }
    }

    /**
     * A key cache for a single value key.
     */
    private final class SingleKeyCache extends KeyCache {

        SingleKeyCache(Class<?> clazz, UUID id, StateKey key) {
            super(clazz, id, key);
        }

        @Override
        protected Observable<?> keyObservable(UUID owner) {
            if (owner == null) {
                return NULL_OBSERVABLE;
            } else {
                return NodeObservable.create(
                    curator, paths.keyPath(owner, clazz, id, key.name()),
                    metrics, false, asFunction(this::closeInternal));
            }
        }
    }

    /**
     * A key cache for a multi value key.
     */
    private final class MultiKeyCache extends KeyCache {

        MultiKeyCache(Class<?> clazz, UUID id, StateKey key) {
            super(clazz, id, key);
        }

        @Override
        protected Observable<?> keyObservable(UUID owner) {
            if (owner == null) {
                return NULL_OBSERVABLE;
            } else {
                return PathDirectoryObservable.create(
                    curator, paths.keyPath(owner, clazz, id, key.name()),
                    metrics, false, asFunction(this::closeInternal));
            }
        }
    }

    private final CuratorFramework curator;
    private final ZoomPaths paths;
    private final StorageMetrics metrics;
    private final Observable<ObjectNotification> objectsObservable;
    private final Map<Class<?>, ClassCache> classes;

    private final Observer<ObjectNotification> objectObserver =
        new Observer<ObjectNotification>() {
            @Override
            public void onNext(ObjectNotification notification) {
                objectNotification(notification);
            }

            @Override
            public void onCompleted() {
                LOG.warn("Unexpected completion of the object cache");

            }

            @Override
            public void onError(Throwable t) {
                LOG.warn("Object cache error", t);
            }
        };
    private Subscription objectSubscription;

    private final SubscriptionList<StateNotification> subscribers =
        new SubscriptionList<StateNotification>() {
            @Override
            public void start(Subscriber<? super StateNotification> child) {
                // Do nothing.
            }

            @Override
            public void added(Subscriber<? super StateNotification> child) {
                notifySnapshot(child);
            }

            @Override
            public void terminated(Subscriber<? super StateNotification> child) {
                child.onCompleted();
            }
        };

    @SuppressWarnings("unchecked")
    private final Observable<StateNotification> observable =
        Observable.create(subscribers);

    public StateCache(CuratorFramework curator,
                      ZoomPaths paths,
                      MetricRegistry metricRegistry,
                      Observable<ObjectNotification> objectObservable) {
        this.curator = curator;
        this.paths = paths;
        this.objectsObservable = objectObservable;
        this.metrics = new StorageMetrics(metricRegistry);

        Map<Class<?>, ClassCache> classes = new HashMap<>(KEYS.size());
        for (Map.Entry<Class<?>, List<StateKey>> entry : KEYS.entrySet()) {
            Class<?> clazz = entry.getKey();
            LOG.debug("Caching state for class {}", clazz.getSimpleName());
            ClassCache cache = new ClassCache(clazz, entry.getValue());
            classes.put(clazz, cache);
        }
        this.classes = Collections.unmodifiableMap(classes);
    }

    /**
     * @return An observable that upon subscription emits a snapshot of the
     * current cache, and subsequently updates with the changes to the cache.
     */
    public Observable<StateNotification> observable() {
        return observable;
    }

    @Override
    protected void doStart() {
        LOG.debug("Initializing NSDB state cache...");

        objectSubscription = objectsObservable.subscribe(objectObserver);

        notifyStarted();
    }

    @Override
    protected void doStop() {
        LOG.debug("Stopping NSDB state cache...");

        if (objectSubscription != null)
            objectSubscription.unsubscribe();

        // Close the cache classes.
        for (Map.Entry<Class<?>, ClassCache> entry : classes.entrySet()) {
            entry.getValue().close();
        }

        notifyStopped();
    }

    /**
     * Handles a notification from the object cache.
     * @param notification The object notification.
     */
    private void objectNotification(ObjectNotification notification) {
        if (notification instanceof ObjectNotification.Update) {
            objectUpdate((ObjectNotification.Update) notification);
        } else if (notification instanceof ObjectNotification.Snapshot) {
            objectSnapshot((ObjectNotification.Snapshot) notification);
        }
    }

    /**
     * Handles an individual object update from the object cache.
     * @param update The object update.
     */
    private void objectUpdate(ObjectNotification.Update update) {
        ClassCache cache = classes.get(update.objectClass());
        // Ignore objects that do not have state.
        if (cache == null)
            return;

        if (update.isDeleted()) {
            cache.remove(update.id());
        } else {
            cache.add(update.id(), update.message());
        }
    }

    /**
     * Handles a snapshot notification from the object cache. This is only
     * emitted upon subscription.
     * @param snapshot The snapshot.
     */
    private void objectSnapshot(ObjectNotification.Snapshot snapshot) {
        for (ObjectNotification.Update update : snapshot) {
            objectUpdate(update);
        }
    }

    /**
     * Notifies an updated entry to all subscribers.
     * @param update The entry.
     */
    private void notifyUpdate(StateNotification.Update update) {
        Subscriber<StateNotification>[] s = subscribers.subscribers();
        LOG.debug("Notifying update to {} subscribers: {}", s.length, update);

        for (Subscriber<StateNotification> child : s) {
            child.onNext(update);
        }
    }

    /**
     * Notifies the current state snapshot to a new subscriber.
     * @param child The child subscriber.
     */
    private void notifySnapshot(Subscriber<? super StateNotification> child) {
        if (!isRunning()) {
            child.onError(new IllegalStateException("State cache not running"));
        }

        StateNotification.Snapshot snapshot = new StateNotification.Snapshot();

        for (Map.Entry<Class<?>, ClassCache> entry : classes.entrySet()) {
            entry.getValue().snapshot(snapshot);
        }

        LOG.debug("Notifying state snapshot to new subscriber: {}", snapshot);
        child.onNext(snapshot);
    }

    /**
     * Converts an action to a Scala function.
     * @param action The action.
     * @return The Scala function.
     */
    private static Function0<BoxedUnit> asFunction(@Nonnull Action0 action) {
        return new AbstractFunction0<BoxedUnit>() {
            @Override
            public BoxedUnit apply() {
                action.call();
                return BoxedUnit.UNIT;
            }
        };
    }

}
