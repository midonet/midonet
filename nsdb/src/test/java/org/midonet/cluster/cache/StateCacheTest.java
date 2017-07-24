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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.Function0;
import scala.concurrent.duration.Duration;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import com.codahale.metrics.MetricRegistry;
import com.typesafe.config.ConfigFactory;

import org.apache.commons.lang.ArrayUtils;
import org.apache.zookeeper.CreateMode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import rx.Observable;
import rx.Subscription;

import org.midonet.cluster.data.storage.KeyType;
import org.midonet.cluster.data.storage.ZookeeperObjectMapper;
import org.midonet.cluster.data.storage.metrics.StorageMetrics;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.State;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend$;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.cluster.util.UUIDUtil$;
import org.midonet.cluster.ZooKeeperTest;
import org.midonet.util.reactivex.TestAwaitableObserver;

public class StateCacheTest extends ZooKeeperTest {

    private static final Function0<BoxedUnit> NO_SETUP =
        new AbstractFunction0<BoxedUnit>() {
            @Override
            public BoxedUnit apply() {
                return BoxedUnit.UNIT;
            }
        };

    private static final Duration TIMEOUT = Duration.apply(1, TimeUnit.SECONDS);
    private static final String NAMESPACE = new UUID(0L, 0L).toString();

    private ZookeeperObjectMapper storage;
    private MidonetBackendConfig config;
    private MetricRegistry metricRegistry;
    private ZoomPaths paths;

    @BeforeClass
    public static void beforeAll() throws Exception {
        ZooKeeperTest.beforeAll(StateCacheTest.class);
    }

    @AfterClass
    public static void afterAll() throws Exception {
        ZooKeeperTest.afterAll(StateCacheTest.class);
    }

    @Before
    @Override
    public void before() throws Exception {
        super.before();

        config = new MidonetBackendConfig(
            ConfigFactory.parseString("zookeeper.root_key : " + ROOT),
            false, false, false);
        paths = new ZoomPaths(config);
        metricRegistry = new MetricRegistry();
        StorageMetrics metrics = new StorageMetrics(metricRegistry);

        storage = new ZookeeperObjectMapper(config, NAMESPACE, curator, curator,
                                            null, null, metrics);

        MidonetBackend$.MODULE$.setupBindings(storage, storage, NO_SETUP);
    }

    @After
    @Override
    public void after() {
        super.after();
    }

    private String getZoomStatePath(UUID owner, Class<?> clazz, UUID id,
                                    String key) {
        return String.format("%s/zoom/0/state/%s/%s/%s/%s", ROOT,
                             owner.toString(), clazz.getSimpleName(),
                             id.toString(), key);
    }

    private String getZoomStateMultiValuePath(UUID owner, Class<?> clazz,
                                              UUID id, String key,
                                              String value) {
        return getZoomStatePath(owner, clazz, id, key) +
               String.format("/%s", value);
    }

    private void addSingle(UUID owner, Class<?> clazz, UUID id, String key,
                           String value) throws Exception {
        String path = getZoomStatePath(owner, clazz, id, key);
        if (curator.checkExists().forPath(path) != null) {
            curator.setData().forPath(path, value.getBytes());
        } else {
            curator.create().creatingParentsIfNeeded()
                   .withMode(CreateMode.EPHEMERAL)
                   .forPath(path, value.getBytes());
        }
    }

    private void removeSingle(UUID owner, Class<?> clazz, UUID id, String key)
        throws Exception {
        String path = getZoomStatePath(owner, clazz, id, key);
        curator.delete().forPath(path);
    }

    private void addMulti(UUID owner, Class<?> clazz, UUID id, String key,
                          String value) throws Exception {
        String path = getZoomStateMultiValuePath(owner, clazz, id, key, value);

        if (curator.checkExists().forPath(path) == null) {
            curator.create().creatingParentContainersIfNeeded()
                .withMode(CreateMode.EPHEMERAL)
                .forPath(path);
        }

    }

    private void addMulti(UUID owner, Class<?> clazz, UUID id, String key,
                          String[] values) throws Exception {
        for (String value : values) {
            addMulti(owner, clazz, id, key, value);
        }
    }

    private void removeMulti(UUID owner, Class<?> clazz, UUID id, String key,
                             String value) throws Exception {
        String path = getZoomStateMultiValuePath(owner, clazz, id, key, value);
        curator.delete().forPath(path);
    }

    private void removeMulti(UUID owner, Class<?> clazz, UUID id, String key,
                             String[] values) throws Exception {
        for (String value : values) {
            removeMulti(owner, clazz, id, key, value);
        }
    }

    private StateNotification.Update updateFor(StateNotification.Snapshot snapshot,
                                               String key) {
        for (StateNotification.Update update : snapshot)
            if (update.key().equals(key))
                return update;
        return null;
    }

    private StateNotification.Update updateFor(List<StateNotification> notifications,
                                               int begin,
                                               String key) {
        for (int index = begin; index < notifications.size(); index++) {
            if (notifications.get(index) instanceof StateNotification.Snapshot) {
                StateNotification.Snapshot snapshot =
                    (StateNotification.Snapshot) notifications.get(index);
                StateNotification.Update update = updateFor(snapshot, key);
                if (update != null)
                    return update;
            } else {
                StateNotification.Update update =
                    (StateNotification.Update) notifications.get(index);
                if (update.key().equals(key))
                    return update;
            }
        }
        return null;
    }

    private void assertUpdateEquals(StateNotification.Update update,
                                    UUID owner, Class<?> clazz, UUID id,
                                    String key, KeyType.KeyTypeVal type) {
        Assert.assertEquals(owner, update.owner());
        Assert.assertEquals(clazz, update.objectClass());
        Assert.assertEquals(id, update.id());
        Assert.assertEquals(key, update.key());
        Assert.assertEquals(type, update.type());
        if (type.isSingle())
            Assert.assertEquals(0, update.singleData().length);
        else
            Assert.assertEquals(0, update.multiData().length);
    }

    private void assertUpdateEquals(StateNotification.Update update,
                                    UUID owner, Class<?> clazz, UUID id,
                                    String key, KeyType.KeyTypeVal type,
                                    byte[] singleValue, String[] multiValue) {
        Assert.assertEquals(owner, update.owner());
        Assert.assertEquals(clazz, update.objectClass());
        Assert.assertEquals(id, update.id());
        Assert.assertEquals(key, update.key());
        Assert.assertEquals(type, update.type());
        if (type.isSingle())
            Assert.assertArrayEquals(singleValue, update.singleData());
        else {
            String[] sortedMultiValue = (String[]) ArrayUtils.clone(multiValue);
            Arrays.sort(sortedMultiValue);

            String[] sortedMultiData = (String[])
                ArrayUtils.clone(update.multiData());
            Arrays.sort(sortedMultiData);

            Assert.assertArrayEquals(sortedMultiValue, sortedMultiValue);
        }
    }

    @Test
    public void testCacheLifecycle() {
        // Given a cache.
        StateCache cache = new StateCache(curator, paths, metricRegistry,
                                          Observable.empty());

        // When the cache is started.
        cache.startAsync().awaitRunning();

        // Then the cache is running.
        Assert.assertTrue(cache.isRunning());

        // When the cache is stopped.
        cache.stopAsync().awaitTerminated();

        // Then the cache is not running.
        Assert.assertFalse(cache.isRunning());

        // And the cache returns the classes.
        Assert.assertTrue(ObjectCache.classes().length > 0);
    }

    @Test
    public void testSubscribeBeforeStart() throws Exception {
        // Given a cache.
        StateCache cache = new StateCache(curator, paths, metricRegistry,
                                          Observable.empty());

        // And an observer.
        TestAwaitableObserver<StateNotification> observer =
            new TestAwaitableObserver<>();

        // When the observer subscribes.
        cache.observable().subscribe(observer);

        // Then the observer receives an error.
        observer.awaitCompletion(TIMEOUT);
        Assert.assertEquals(1, observer.getOnErrorEvents().size());
        Assert.assertEquals(IllegalStateException.class,
                            observer.getOnErrorEvents().get(0).getClass());
    }

    @Test
    public void testCachePublishesHostState() throws Exception {
        // Given an object and state cache.
        ObjectCache objectCache = new ObjectCache(curator, paths, metricRegistry);
        StateCache cache = new StateCache(curator, paths, metricRegistry,
                                          objectCache.observable());

        // And an observer.
        TestAwaitableObserver<StateNotification> observer1 =
            new TestAwaitableObserver<>();

        // When the cache is started.
        objectCache.startAsync().awaitRunning();
        cache.startAsync().awaitRunning();

        // And the observer subscribes.
        Subscription sub1 = cache.observable().subscribe(observer1);

        // Then the observer should receive an empty snapshot notification.
        observer1.awaitOnNext(1, TIMEOUT);
        StateNotification.Snapshot snapshot =
            (StateNotification.Snapshot) observer1.getOnNextEvents().get(0);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 1);
        Assert.assertTrue(snapshot.isEmpty());

        // When adding a host to the topology.
        UUID id = UUID.randomUUID();
        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(id))
            .build();
        storage.create(host);

        // Then the observer receives two notifications.
        observer1.awaitOnNext(3, TIMEOUT);

        StateNotification.Update update =
            (StateNotification.Update) observer1.getOnNextEvents().get(1);
        Assert.assertEquals(update.singleData().length, 0);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(2);
        Assert.assertEquals(update.singleData().length, 0);

        // When adding host alive state.
        addSingle(id, Topology.Host.class, id, "alive", "true");

        // Then the observer receives a notification.
        observer1.awaitOnNext(4, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(3);
        assertUpdateEquals(update, id, Topology.Host.class, id, "alive",
                           KeyType.SingleLastWriteWins(), "true".getBytes(), null);

        // When updating host alive state.
        addSingle(id, Topology.Host.class, id, "alive", "false");

        // Then the observer receives a notification.
        observer1.awaitOnNext(5, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(4);
        assertUpdateEquals(update, id, Topology.Host.class, id, "alive",
                           KeyType.SingleLastWriteWins(), "false".getBytes(), null);

        // When removing the state key.
        removeSingle(id, Topology.Host.class, id, "alive");

        // Then the observer receives a notification.
        observer1.awaitOnNext(6, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(5);
        assertUpdateEquals(update, id, Topology.Host.class, id, "alive",
                           KeyType.SingleLastWriteWins());

        // When re-adding the state key.
        addSingle(id, Topology.Host.class, id, "alive", "alive");

        // Then the observer receives a notification.
        observer1.awaitOnNext(7, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(6);
        assertUpdateEquals(update, id, Topology.Host.class, id, "alive",
                           KeyType.SingleLastWriteWins(), "alive".getBytes(), null);

        // Given a second observer.
        TestAwaitableObserver<StateNotification> observer2 =
            new TestAwaitableObserver<>();

        // When the observer subscribes.
        Subscription sub2 = cache.observable().subscribe(observer2);

        // Then the observer receives a snapshot with the current state.
        observer2.awaitOnNext(1, TIMEOUT);
        snapshot =
            (StateNotification.Snapshot) observer2.getOnNextEvents().get(0);

        Assert.assertEquals(observer2.getOnNextEvents().size(), 1);
        Assert.assertEquals(snapshot.size(), 2);

        assertUpdateEquals(updateFor(snapshot, "alive"), id, Topology.Host.class,
                           id, "alive", KeyType.SingleLastWriteWins(),
                           "alive".getBytes(), null);
        assertUpdateEquals(updateFor(snapshot, "host"), id, Topology.Host.class,
                           id, "host", KeyType.SingleLastWriteWins());

        // When updating the host entry.
        State.HostState hostState = State.HostState.newBuilder()
            .setHostId(Commons.UUID.newBuilder().setLsb(1L).setMsb(1L))
            .build();
        addSingle(id, Topology.Host.class, id, "host", hostState.toString());

        // Then both observers should receive a notification.
        observer1.awaitOnNext(8, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(7);
        assertUpdateEquals(update, id, Topology.Host.class, id, "host",
                           KeyType.SingleLastWriteWins(), hostState.toByteArray(),
                           null);

        observer2.awaitOnNext(2, TIMEOUT);

        update = (StateNotification.Update) observer2.getOnNextEvents().get(1);
        assertUpdateEquals(update, id, Topology.Host.class, id, "host",
                           KeyType.SingleLastWriteWins(), hostState.toByteArray(),
                           null);

        // When deleting the object.
        storage.delete(Topology.Host.class, id);

        // Then both observers should receive two notifications.
        observer1.awaitOnNext(10, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(8);
        Assert.assertEquals(update.owner(), null);
        update = (StateNotification.Update) observer1.getOnNextEvents().get(9);
        Assert.assertEquals(update.owner(), null);

        observer2.awaitOnNext(4, TIMEOUT);

        update = (StateNotification.Update) observer2.getOnNextEvents().get(2);
        Assert.assertEquals(update.owner(), null);
        update = (StateNotification.Update) observer2.getOnNextEvents().get(3);
        Assert.assertEquals(update.owner(), null);

        // When creating a new object.
        storage.create(host);

        // Then both observers should receive notifications.
        observer1.awaitOnNext(12, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(10);
        Assert.assertEquals(update.owner(), id);
        update = (StateNotification.Update) observer1.getOnNextEvents().get(11);
        Assert.assertEquals(update.owner(), id);

        observer2.awaitOnNext(6, TIMEOUT);

        update = (StateNotification.Update) observer2.getOnNextEvents().get(4);
        Assert.assertEquals(update.owner(), id);
        update = (StateNotification.Update) observer2.getOnNextEvents().get(5);
        Assert.assertEquals(update.owner(), id);

        // When the cache is stopped.
        cache.stopAsync().awaitTerminated();

        // Then both observers should receive notifications.
        observer1.awaitOnNext(14, TIMEOUT);

        update = (StateNotification.Update) observer1.getOnNextEvents().get(12);
        Assert.assertEquals(update.owner(), null);
        update = (StateNotification.Update) observer1.getOnNextEvents().get(13);
        Assert.assertEquals(update.owner(), null);

        observer2.awaitOnNext(8, TIMEOUT);

        update = (StateNotification.Update) observer2.getOnNextEvents().get(6);
        Assert.assertEquals(update.owner(), null);
        update = (StateNotification.Update) observer2.getOnNextEvents().get(7);
        Assert.assertEquals(update.owner(), null);

        objectCache.stopAsync().awaitTerminated();
    }

    @Test
    public void testCachePublishesPortActiveState() throws Exception {
        // Given an object and state cache.
        ObjectCache objectCache = new ObjectCache(curator, paths, metricRegistry);
        StateCache cache = new StateCache(curator, paths, metricRegistry,
                                          objectCache.observable());

        // And an observer.
        TestAwaitableObserver<StateNotification> observer1 =
            new TestAwaitableObserver<>();

        // When the cache is started.
        objectCache.startAsync().awaitRunning();
        cache.startAsync().awaitRunning();

        // And the observer subscribes.
        cache.observable().subscribe(observer1);

        // Then the observer should receive an empty snapshot notification.
        observer1.awaitOnNext(1, TIMEOUT);
        StateNotification.Snapshot snapshot =
            (StateNotification.Snapshot) observer1.getOnNextEvents().get(0);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 1);
        Assert.assertTrue(snapshot.isEmpty());

        // When adding a host to the topology.
        UUID hostId = UUID.randomUUID();
        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(hostId))
            .build();
        storage.create(host);

        // Then the observer receives three notifications, one snapshot and one
        // for each key registered for a host.
        observer1.awaitOnNext(3, TIMEOUT);

        // When adding a port to the topology.
        UUID portId = UUID.randomUUID();
        Topology.Port port = Topology.Port.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(portId))
            .build();
        storage.create(port);

        // Then the observer receives three more notifications, one for each
        // state key registered for a port.
        observer1.awaitOnNext(6, TIMEOUT);

        // And one update should be port inactive.
        StateNotification.Update update = updateFor(observer1.getOnNextEvents(),
                                                    3, "active");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "active",
                           KeyType.SingleLastWriteWins());

        // When making the port active.
        State.PortState state = State.PortState.newBuilder()
            .setHostId(UUIDUtil$.MODULE$.toProto(hostId)).build();
        addSingle(hostId, Topology.Port.class, portId, "active", state.toString());

        // Then the observer should not receive a new notification.
        Assert.assertEquals(6, observer1.getOnNextEvents().size());

        // When the port is bound to the host.
        port = port.toBuilder().setHostId(UUIDUtil$.MODULE$.toProto(hostId))
            .build();
        storage.update(port);

        // Then the observer receives three new notifications.
        observer1.awaitOnNext(9, TIMEOUT);

        // And the notification is port active.
        update = updateFor(observer1.getOnNextEvents(), 6, "active");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "active",
                           KeyType.SingleLastWriteWins(), state.toByteArray(), null);

        // When removing the port state.
        removeSingle(hostId, Topology.Port.class, portId, "active");

        // Then the observer receives a new notifications.
        observer1.awaitOnNext(10, TIMEOUT);

        // And the update should be port inactive.
        update = updateFor(observer1.getOnNextEvents(), 9, "active");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "active",
                           KeyType.SingleLastWriteWins());

        // When making the port active.
        addSingle(hostId, Topology.Port.class, portId, "active", state.toString());

        // Then the observer receives a new notifications.
        observer1.awaitOnNext(11, TIMEOUT);

        // And the update should be port inactive.
        update = updateFor(observer1.getOnNextEvents(), 10, "active");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "active",
                           KeyType.SingleLastWriteWins(), state.toByteArray(), null);

        // When removing the port from the host.
        port = port.toBuilder().clearHostId().build();
        storage.update(port);

        // Then the observer receives three new notifications.
        observer1.awaitOnNext(14, TIMEOUT);

        // And one notification is port inactive.
        update = updateFor(observer1.getOnNextEvents(), 11, "active");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "active",
                           KeyType.SingleLastWriteWins());

        cache.stopAsync().awaitTerminated();
        objectCache.stopAsync().awaitTerminated();
    }

    @Test
    public void testCachePublishesPortRoutesState() throws Exception {
        // Given an object and state cache.
        ObjectCache objectCache = new ObjectCache(curator, paths, metricRegistry);
        StateCache cache = new StateCache(curator, paths, metricRegistry,
                                          objectCache.observable());

        // And an observer.
        TestAwaitableObserver<StateNotification> observer1 =
            new TestAwaitableObserver<>();

        // When the cache is started.
        objectCache.startAsync().awaitRunning();
        cache.startAsync().awaitRunning();

        // And the observer subscribes.
        cache.observable().subscribe(observer1);

        // Then the observer should receive an empty snapshot notification.
        observer1.awaitOnNext(1, TIMEOUT);
        StateNotification.Snapshot snapshot =
            (StateNotification.Snapshot) observer1.getOnNextEvents().get(0);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 1);
        Assert.assertTrue(snapshot.isEmpty());

        // When adding a host to the topology.
        UUID hostId = UUID.randomUUID();
        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(hostId))
            .build();
        storage.create(host);

        // Then the observer receives three notifications, one snapshot and one
        // for each key registered for a host.
        observer1.awaitOnNext(3, TIMEOUT);

        // When adding a port to the topology
        UUID portId = UUID.randomUUID();
        Topology.Port port = Topology.Port.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(portId))
            .build();
        storage.create(port);

        // Then the observer receives three more notifications, one for each
        // state key registered for a port.
        observer1.awaitOnNext(6, TIMEOUT);

        // And one update should be port routes.
        StateNotification.Update update = updateFor(observer1.getOnNextEvents(),
                                                    3, "routes");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "routes",
                           KeyType.Multiple());

        // When adding port routes state (just random strings).
        String[] routes = new String[] {
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
        };
        addMulti(hostId, Topology.Port.class, portId, "routes", routes);

        // Then the observer should not receive a new notification.
        Assert.assertEquals(6, observer1.getOnNextEvents().size());

        // When the port is bound to the host.
        port = port.toBuilder()
            .setHostId(UUIDUtil$.MODULE$.toProto(hostId))
            .build();
        storage.update(port);

        // Then the observer receives three new notifications.
        observer1.awaitOnNext(9, TIMEOUT);

        // And the notification is port routes.
        update = updateFor(observer1.getOnNextEvents(), 6, "routes");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "routes",
                           KeyType.Multiple(), null, routes);

        // When removing a port route.
        removeMulti(hostId, Topology.Port.class, portId, "routes", routes[0]);

        // Then the observer receives a new notification.
        observer1.awaitOnNext(10, TIMEOUT);

        // And the notification is port routes.
        update = updateFor(observer1.getOnNextEvents(), 9, "routes");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "routes",
                           KeyType.Multiple(), null,
                           new String[]{routes[1], routes[2], routes[3]});

        // When re-adding the port route.
        addMulti(hostId, Topology.Port.class, portId, "routes", routes[0]);

        // Then the observer receives a new notification.
        observer1.awaitOnNext(11, TIMEOUT);

        // And the notification is port routes.
        update = updateFor(observer1.getOnNextEvents(), 10, "routes");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "routes",
                           KeyType.Multiple(), null, routes);

        // Given a second observer.
        TestAwaitableObserver<StateNotification> observer2 =
            new TestAwaitableObserver<>();

        // When the observer subscribes.
        cache.observable().subscribe(observer2);

        // Then the observer receives a snapshot with the current snapshot.
        observer2.awaitOnNext(1, TIMEOUT);
        snapshot =
            (StateNotification.Snapshot) observer2.getOnNextEvents().get(0);
        update = updateFor(snapshot, "routes");

        // And the snapshot includes five notifications.
        Assert.assertEquals(snapshot.size(), 5);

        // And one notification is a port routes notification.
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "routes",
                           KeyType.Multiple(), null, routes);

        // When deleting the object.
        storage.delete(Topology.Port.class, portId);

        // Then both observers should receive three more notifications.
        observer1.awaitOnNext(14, TIMEOUT);
        observer2.awaitOnNext(4, TIMEOUT);

        // And these should include a routes notification.
        update = updateFor(observer1.getOnNextEvents(), 11, "routes");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "routes",
                           KeyType.Multiple());

        update = updateFor(observer2.getOnNextEvents(), 1, "routes");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "routes",
                           KeyType.Multiple());

        cache.stopAsync().awaitTerminated();
        objectCache.stopAsync().awaitTerminated();
    }

    @Test
    public void testCachePublishesPortBgpState() throws Exception {
        // Given an object and state cache.
        ObjectCache objectCache = new ObjectCache(curator, paths, metricRegistry);
        StateCache cache = new StateCache(curator, paths, metricRegistry,
                                          objectCache.observable());

        // And an observer.
        TestAwaitableObserver<StateNotification> observer1 =
            new TestAwaitableObserver<>();

        // When the cache is started.
        objectCache.startAsync().awaitRunning();
        cache.startAsync().awaitRunning();

        // And the observer subscribes.
        cache.observable().subscribe(observer1);

        // Then the observer should receive an empty snapshot notification.
        observer1.awaitOnNext(1, TIMEOUT);
        StateNotification.Snapshot snapshot =
            (StateNotification.Snapshot) observer1.getOnNextEvents().get(0);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 1);
        Assert.assertTrue(snapshot.isEmpty());

        // When adding a host to the topology.
        UUID hostId = UUID.randomUUID();
        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(hostId))
            .build();
        storage.create(host);

        // Then the observer receives three notifications, one snapshot and one
        // for each key registered for a host.
        observer1.awaitOnNext(3, TIMEOUT);

        // When adding a port to the topology
        UUID portId = UUID.randomUUID();
        Topology.Port port = Topology.Port.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(portId))
            .build();
        storage.create(port);

        // Then the observer receives three more notifications, one for each
        // state key registered for a port.
        observer1.awaitOnNext(6, TIMEOUT);

        // And one update should be port BGP.
        StateNotification.Update update = updateFor(observer1.getOnNextEvents(),
                                                    3, "bgp");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins());

        // When adding port BGP state.
        addSingle(hostId, Topology.Port.class, portId, "bgp", "data0");

        // Then the observer should not receive a new notification.
        Assert.assertEquals(6, observer1.getOnNextEvents().size());

        // When the port is bound to the host.
        port = port.toBuilder()
            .setHostId(UUIDUtil$.MODULE$.toProto(hostId))
            .build();
        storage.update(port);

        // Then the observer receives three new notifications.
        observer1.awaitOnNext(9, TIMEOUT);

        // And the notification is port BGP.
        update = updateFor(observer1.getOnNextEvents(), 6, "bgp");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins(), "data0".getBytes(), null);

        // When updating the BGP state.
        addSingle(hostId, Topology.Port.class, portId, "bgp", "data1");

        // Then the observer receives a new notification.
        observer1.awaitOnNext(10, TIMEOUT);

        // And the notification is port BGP.
        update = updateFor(observer1.getOnNextEvents(), 9, "bgp");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins(), "data1".getBytes(), null);

        // When removing the state key.
        removeSingle(hostId, Topology.Port.class, portId, "bgp");

        // Then the observer receives a new notification.
        observer1.awaitOnNext(11, TIMEOUT);

        // And the notification is port BGP.
        update = updateFor(observer1.getOnNextEvents(), 10, "bgp");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins());

        // When re-adding the state key.
        addSingle(hostId, Topology.Port.class, portId, "bgp", "data2");

        // Then the observer receives a new notification.
        observer1.awaitOnNext(12, TIMEOUT);

        // And the notification is port BGP.
        update = updateFor(observer1.getOnNextEvents(), 11, "bgp");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins(), "data2".getBytes(), null);

        // Given a second observer.
        TestAwaitableObserver<StateNotification> observer2 =
            new TestAwaitableObserver<>();

        // When the observer subscribes.
        cache.observable().subscribe(observer2);

        // Then the observer receives a snapshot with the current snapshot.
        observer2.awaitOnNext(1, TIMEOUT);
        snapshot =
            (StateNotification.Snapshot) observer2.getOnNextEvents().get(0);
        update = updateFor(snapshot, "bgp");

        // And the snapshot includes five notifications.
        Assert.assertEquals(snapshot.size(), 5);

        // And one notification is a port routes notification.
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins(), "data2".getBytes(), null);

        // When updating the bgp entry.
        addSingle(hostId, Topology.Port.class, portId, "bgp", "data3");

        // Then both observers should receive a notification.
        observer1.awaitOnNext(13, TIMEOUT);
        observer2.awaitOnNext(2, TIMEOUT);

        update = updateFor(observer1.getOnNextEvents(), 12, "bgp");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins(), "data3".getBytes(), null);

        update = updateFor(observer2.getOnNextEvents(), 1, "bgp");
        assertUpdateEquals(update, hostId, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins(), "data3".getBytes(), null);

        // When deleting the object.
        storage.delete(Topology.Port.class, portId);

        // Then both observers should receive three more notifications.
        observer1.awaitOnNext(16, TIMEOUT);
        observer2.awaitOnNext(5, TIMEOUT);

        // And these should include a routes notification.
        update = updateFor(observer1.getOnNextEvents(), 13, "bgp");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins());

        update = updateFor(observer2.getOnNextEvents(), 2, "bgp");
        assertUpdateEquals(update, null, Topology.Port.class, portId, "bgp",
                           KeyType.SingleLastWriteWins());

        cache.stopAsync().awaitTerminated();
        objectCache.stopAsync().awaitTerminated();
    }
}
