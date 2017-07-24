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

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import scala.Function0;
import scala.collection.Iterator;
import scala.collection.Set;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractService;
import com.google.protobuf.Message;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action2;

import org.midonet.cluster.data.storage.metrics.StorageMetrics;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.NodeObservable;
import org.midonet.cluster.util.PathDirectoryObservable;
import org.midonet.util.reactivex.SubscriptionList;

/**
 * Implements a cache for the Network State Database (NSDB) objects. Upon start,
 * an instance of this class exposes an {@link Observable} that when subscribed
 * to emits notifications with the current NSDB objects.
 */
@SuppressWarnings("unused")
public final class ObjectCache extends AbstractService {

    private static final Logger LOG = LoggerFactory.getLogger(ObjectCache.class);

    private static final Class<?>[] CLASSES = new Class[] {
        Topology.BgpNetwork.class,
        Topology.BgpPeer.class,
        Topology.Chain.class,
        Topology.Dhcp.class,
        Topology.DhcpV6.class,
        Topology.HealthMonitor.class,
        Topology.Host.class,
        Topology.HostGroup.class,
        Topology.IPAddrGroup.class,
        Topology.L2Insertion.class,
        Topology.LoadBalancer.class,
        Topology.LoggingResource.class,
        Topology.Mirror.class,
        Topology.Network.class,
        Topology.Pool.class,
        Topology.PoolMember.class,
        Topology.Port.class,
        Topology.PortGroup.class,
        Topology.QosPolicy.class,
        Topology.QosRuleBandwidthLimit.class,
        Topology.QosRuleDscp.class,
        Topology.Route.class,
        Topology.Router.class,
        Topology.Rule.class,
        Topology.RuleLogger.class,
        Topology.ServiceContainer.class,
        Topology.ServiceContainerGroup.class,
        Topology.TraceRequest.class,
        Topology.TunnelZone.class,
        Topology.Vip.class,
        Topology.Vtep.class,
        Neutron.AgentMembership.class,
        Neutron.FloatingIp.class,
        Neutron.FirewallLog.class,
        Neutron.GatewayDevice.class,
        Neutron.IPSecSiteConnection.class,
        Neutron.L2GatewayConnection.class,
        Neutron.NeutronBgpPeer.class,
        Neutron.NeutronBgpSpeaker.class,
        Neutron.NeutronConfig.class,
        Neutron.NeutronFirewall.class,
        Neutron.NeutronHealthMonitor.class,
        Neutron.NeutronLoadBalancerPool.class,
        Neutron.NeutronLoadBalancerPoolMember.class,
        Neutron.NeutronLoggingResource.class,
        Neutron.NeutronNetwork.class,
        Neutron.NeutronPort.class,
        Neutron.NeutronRouter.class,
        Neutron.NeutronRouterInterface.class,
        Neutron.NeutronSubnet.class,
        Neutron.NeutronVIP.class,
        Neutron.PortBinding.class,
        Neutron.RemoteMacEntry.class,
        Neutron.SecurityGroup.class,
        Neutron.SecurityGroupRule.class,
        Neutron.TapFlow.class,
        Neutron.TapService.class,
        Neutron.VpnService.class
    };

    /**
     * A data for an object class.
     */
    private final class ClassCache implements AutoCloseable {

        private final Class<?> clazz;
        private final String path;
        private final PathDirectoryObservable observable;
        private final ObjectSerializer serializer;

        private final Map<String, ObjCache> objects;
        private boolean closing = false;

        private final Subscriber<Set<String>> subscriber = new Subscriber<Set<String>>() {
            @Override
            public void onCompleted() {
                LOG.warn("Notifications for class {} completed",
                         clazz.getSimpleName());
            }

            @Override
            public void onError(Throwable e) {
                LOG.warn("Unexpected error for class {}",
                         clazz.getSimpleName(), e);
            }

            @Override
            public void onNext(Set<String> ids) {
                LOG.trace("Class {} cache objects changed: {}",
                          clazz.getSimpleName(), ids);
                refresh(ids);
            }
        };

        ClassCache(Class<?> clazz) {
            this.clazz = clazz;
            path = paths.objectClassPath(clazz);
            serializer = ObjectMessaging.serializerOf(clazz);

            objects = new HashMap<>();
            observable = PathDirectoryObservable.create(curator, path, metrics,
                                                        true, asFunction(this::closeInternal));

            if (serializer == null) {
                LOG.warn("No serializer for object class {}", clazz);
            }
        }

        /**
         * Starts the cache for this class.
         */
        void start() {
            observable.subscribe(subscriber);
        }

        /**
         * Stops caching the current object class by unsubscribing from the
         * underlying observable and closing it.
         */
        @Override
        public void close() {
            LOG.debug("Class {} object cache closed", clazz.getSimpleName());
            subscriber.unsubscribe();
            observable.close();
            closeInternal();
        }

        /**
         * Adds a snapshot of the current data to the given entries
         * notification.
         */
        synchronized void snapshot(ObjectNotification.Snapshot snapshot) {
            for (Map.Entry<String, ObjCache> entry : objects.entrySet()) {
                entry.getValue().snapshot(snapshot);
            }
        }

        /**
         * Closes the objects cache for this class.
         */
        private synchronized void closeInternal() {
            closing = true;
            for (Map.Entry<String, ObjCache> entry : objects.entrySet()) {
                entry.getValue().close();
            }
        }

        /**
         * Refreshes the current data class with the specified list of objects.
         * @param ids The set of object identifiers.
         */
        private synchronized void refresh(Set<String> ids) {
            // Add all new objects that are not already cached. The objects
            // removed from the data are removed when their corresponding
            // data closes.
            Iterator<String> iterator = ids.iterator();
            while (iterator.hasNext()) {
                String id = iterator.next();
                if (!objects.containsKey(id)) {
                    add(id);
                }
            }
        }

        /**
         * Adds a new object to the data. This method requires
         * synchronization.
         * @param id The object identifier.
         */
        private void add(String id) {
            try {
                UUID uuid = UUID.fromString(id);
                LOG.debug("Class {} object cache added object: {}",
                          clazz.getSimpleName(), uuid);
                ObjCache cache =
                    new ObjCache(clazz, id, paths.objectPath(path, id),
                                 uuid, serializer, this::remove);
                // Do not add the cache to the map if already closed.
                if (!cache.isClosed())
                    objects.put(id, cache);
            } catch (Exception e) {
                LOG.info("Skip caching of object with invalid identifier {}:{}",
                         clazz.getSimpleName(), id, e);
            }
        }

        /**
         * Removes an existing object from the data if present, and closes
         * its corresponding data.
         * @param id The object identifier.
         * @param cache The object data to check for consistency.
         */
        private synchronized void remove(String id, ObjCache cache) {
            if (!closing) {
                LOG.debug("Class {} object cache removed object: {}",
                          clazz.getSimpleName(), cache.uuid);
                objects.remove(id, cache);
            }
        }

    }

    /**
     * A cache for an object.
     */
    private final class ObjCache implements AutoCloseable,
                                            ObjectNotification.Update {

        private final Class<?> clazz;
        private final String id;
        private final UUID uuid;
        private final ObjectSerializer serializer;

        private final NodeObservable observable;
        private final Action2<String, ObjCache> onClose;

        private volatile ChildData data;
        private volatile Message message;
        private volatile boolean deleted;

        private final Subscriber<ChildData> subscriber = new Subscriber<ChildData>() {
            @Override
            public void onCompleted() {
                LOG.debug("Object {}:{} deleted", clazz.getSimpleName(), id);
                deleted = true;
                data = null;
                notifyUpdate(ObjCache.this);
                close();
            }

            @Override
            public void onError(Throwable e) {
                LOG.info("Error for object {}:{}", clazz.getSimpleName(), id, e);
                close();
            }

            @Override
            public void onNext(ChildData childData) {
                LOG.debug("Object {}:{} updated: {}", clazz.getSimpleName(),
                          uuid, childData);
                refresh(childData);
            }
        };

        ObjCache(Class<?> clazz, String id, String path, UUID uuid,
                 ObjectSerializer serializer, Action2<String, ObjCache> onClose) {
            this.clazz = clazz;
            this.id = id;
            this.uuid = uuid;
            this.serializer = serializer;
            this.onClose = onClose;

            observable = NodeObservable.create(curator, path, metrics, true,
                                               asFunction(this::closeInternal));
            observable.subscribe(subscriber);
        }

        /**
         * Closes the current object cache and notifies all subscribers that
         * the object has been deleted.
         */
        @Override
        public void close() {
            LOG.debug("Object {}:{} cache closed", clazz.getSimpleName(), uuid);
            subscriber.unsubscribe();
            observable.close();
            closeInternal();
        }

        /**
         * @return True if the object cache is closed.
         */
        public boolean isClosed() {
            return observable.isClosed();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Class<?> objectClass() {
            return clazz;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public UUID id() {
            return uuid;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Nullable
        public ChildData childData() {
            return data;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Nullable
        public Message message() {
            return message;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isDeleted() {
            return deleted;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                .add("class", clazz.getSimpleName())
                .add("id", uuid)
                .add("data", data)
                .add("deleted", deleted)
                .toString();
        }

        /**
         * Requests a snapshot for the current object to add it to the given
         * entries list.
         * @param snapshot The entries notification.
         */
        synchronized void snapshot(Snapshot snapshot) {
            if (data != null) {
                snapshot.add(this);
            }
        }

        /**
         * Calls the close handler for this object cache.
         */
        private void closeInternal() {
            onClose.call(id, this);
        }

        /**
         * Refreshes the data for the current object and notifies all
         * subscribers.
         * @param data The object data.
         */
        synchronized private void refresh(ChildData data) {
            if (serializer != null && data.getData() != null) {
                try {
                    message = serializer.convertTextToMessage(data.getData());
                } catch (IOException e) {
                    LOG.warn("Failed to convert object {}:{} with data {}",
                             clazz, id, new String(data.getData()), e);
                    message = null;
                }
            }
            this.data = data;
            notifyUpdate(this);
        }
    }

    private final CuratorFramework curator;
    private final ZoomPaths paths;
    private final StorageMetrics metrics;
    private final Map<Class<?>, ClassCache> classes;

    private final SubscriptionList<ObjectNotification> subscribers =
        new SubscriptionList<ObjectNotification>() {
            @Override
            public void start(Subscriber<? super ObjectNotification> child) {
                // Do nothing.
            }

            @Override
            public void added(Subscriber<? super ObjectNotification> child) {
                notifySnapshot(child);
            }

            @Override
            public void terminated(Subscriber<? super ObjectNotification> child) {
                child.onCompleted();
            }
        };

    @SuppressWarnings("unchecked")
    private final Observable<ObjectNotification> observable =
        Observable.create(subscribers);

    @SuppressWarnings("unchecked")
    public ObjectCache(CuratorFramework curator,
                       ZoomPaths paths,
                       MetricRegistry metricRegistry) {
        this.curator = curator;
        this.paths = paths;
        this.metrics = new StorageMetrics(metricRegistry);

        Map<Class<?>, ClassCache> classes = new HashMap<>(CLASSES.length);
        for (Class<?> clazz : CLASSES) {
            LOG.debug("Initializing object class {}", clazz.getSimpleName());
            ClassCache cache = new ClassCache(clazz);
            classes.put(clazz, cache);
        }
        this.classes = Collections.unmodifiableMap(classes);
    }

    /**
     * @return The object classes supported by the NSDB cache.
     */
    public static Class<?>[] classes() {
        return CLASSES;
    }

    /**
     * @return An observable that upon subscription emits a snapshot of the
     * current cache, and subsequently updates with the changes to the
     * cache.
     */
    public Observable<ObjectNotification> observable() {
        return observable;
    }

    @Override
    protected void doStart() {
        LOG.debug("Initializing NSDB object cache...");

        for (Map.Entry<Class<?>, ClassCache> entry : classes.entrySet()) {
            LOG.debug("Subscribing to class {}", entry.getKey().getSimpleName());
            entry.getValue().start();
        }

        notifyStarted();
    }

    @Override
    protected void doStop() {
        LOG.debug("Stopping NSDB object cache...");

        // Complete all subscribers.
        for (Subscriber<ObjectNotification> subscriber : subscribers.terminate()) {
            subscriber.onCompleted();
        }

        // Close all class caches.
        for (Map.Entry<Class<?>, ClassCache> entry : classes.entrySet()) {
            entry.getValue().close();
        }

        notifyStopped();
    }

    /**
     * Notifies an updated entry to all subscribers.
     * @param update The entry.
     */
    private void notifyUpdate(ObjectNotification.Update update) {
        Subscriber<ObjectNotification>[] s = subscribers.subscribers();
        LOG.debug("Notifying update to {} subscribers: {}", s.length, update);

        for (Subscriber<ObjectNotification> child : s) {
            child.onNext(update);
        }
    }

    /**
     * Notifies the current data snapshot to a new subscriber.
     * @param child The child subscriber.
     */
    private void notifySnapshot(Subscriber<? super ObjectNotification> child) {
        if (!isRunning()) {
            child.onError(new IllegalStateException("Object cache not running"));
            return;
        }

        ObjectNotification.Snapshot snapshot = new ObjectNotification.Snapshot();

        for (Map.Entry<Class<?>, ClassCache> entry : classes.entrySet()) {
            entry.getValue().snapshot(snapshot);
        }

        LOG.debug("Notifying object snapshot to new subscriber: {}", snapshot);

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
