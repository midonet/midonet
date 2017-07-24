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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.Function0;
import scala.concurrent.duration.Duration;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.Message;
import com.typesafe.config.ConfigFactory;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import rx.Subscription;

import org.midonet.cluster.data.storage.ZookeeperObjectMapper;
import org.midonet.cluster.data.storage.metrics.StorageMetrics;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend$;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.cluster.util.UUIDUtil$;
import org.midonet.cluster.ZooKeeperTest;
import org.midonet.util.reactivex.TestAwaitableObserver;

public class ObjectCacheTest extends ZooKeeperTest {

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
        ZooKeeperTest.beforeAll(ObjectCacheTest.class);
    }

    @AfterClass
    public static void afterAll() throws Exception {
        ZooKeeperTest.afterAll(ObjectCacheTest.class);
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

    private void assertUpdateEquals(ObjectNotification.Update update,
                                    UUID id, Message object, int version) {
        Assert.assertEquals(update.id(), id);
        Assert.assertEquals(update.objectClass(), object.getClass());
        Assert.assertEquals(update.childData().getStat().getVersion(), version);
        Assert.assertEquals(update.message(), object);
        Assert.assertArrayEquals(update.childData().getData(),
                                 object.toString().getBytes());
        Assert.assertFalse(update.isDeleted());
    }

    private void assertDelete(ObjectNotification.Update update, Class<?> clazz,
                              UUID id) {
        Assert.assertEquals(update.id(), id);
        Assert.assertEquals(update.objectClass(), clazz);
        Assert.assertNull(update.childData());
        Assert.assertTrue(update.isDeleted());
    }

    @Test
    public void testCacheLifecycle() {
        // Given a cache.
        ObjectCache cache = new ObjectCache(curator, paths, metricRegistry);

        // When the cache is started.
        cache.startAsync().awaitRunning();

        // Then the cache is running.
        Assert.assertTrue(cache.isRunning());

        // When the cache is stopped.
        cache.stopAsync().awaitTerminated();

        // Then the cache is not running.
        Assert.assertFalse(cache.isRunning());
    }

    @Test
    public void testSubscribeBeforeStart() throws Exception {
        // Given a cache.
        ObjectCache cache = new ObjectCache(curator, paths, metricRegistry);

        // And an observer.
        TestAwaitableObserver<ObjectNotification> observer =
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
    public void testCachePublishesUpdates() throws Exception {
        // Given a cache.
        ObjectCache cache = new ObjectCache(curator, paths, metricRegistry);

        // And an observer.
        TestAwaitableObserver<ObjectNotification> observer1 =
            new TestAwaitableObserver<>();

        // When the cache is started.
        cache.startAsync().awaitRunning();

        // And the subscriber subscribes.
        Subscription sub1 = cache.observable().subscribe(observer1);

        // Then the observer should receive an empty snapshot notification.
        observer1.awaitOnNext(1, TIMEOUT);
        ObjectNotification.Snapshot snapshot =
            (ObjectNotification.Snapshot) observer1.getOnNextEvents().get(0);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 1);
        Assert.assertTrue(snapshot.isEmpty());

        // When adding an object to the topology.
        UUID id1 = UUID.randomUUID();
        Topology.Network network = Topology.Network.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(id1))
            .build();
        storage.create(network);

        // Then the observer should receive an update notification.
        observer1.awaitOnNext(2, TIMEOUT);
        ObjectNotification.Update update =
            (ObjectNotification.Update) observer1.getOnNextEvents().get(1);
        assertUpdateEquals(update, id1, network, 0);

        // When a new observer subscribes.
        TestAwaitableObserver<ObjectNotification> observer2 =
            new TestAwaitableObserver<>();
        cache.observable().subscribe(observer2);

        // Then the observes receives a snapshot with the object.
        observer2.awaitOnNext(1, TIMEOUT);
        snapshot =
            (ObjectNotification.Snapshot) observer2.getOnNextEvents().get(0);

        Assert.assertEquals(observer2.getOnNextEvents().size(), 1);
        Assert.assertEquals(snapshot.size(), 1);
        assertUpdateEquals(snapshot.get(0), id1, network, 0);

        // When updating the object.
        network = network.toBuilder().setName("name").build();
        storage.update(network);

        // Then the first observer receives a notification.
        observer1.awaitOnNext(3, TIMEOUT);
        update =
            (ObjectNotification.Update) observer1.getOnNextEvents().get(2);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 3);
        assertUpdateEquals(update, id1, network, 1);

        // And the second observer receives a notification.
        observer2.awaitOnNext(2, TIMEOUT);
        update =
            (ObjectNotification.Update) observer2.getOnNextEvents().get(1);

        Assert.assertEquals(observer2.getOnNextEvents().size(), 2);
        assertUpdateEquals(update, id1, network, 1);

        // When creating a new object.
        UUID id2 = UUID.randomUUID();
        Topology.Router router = Topology.Router.newBuilder()
            .setId(UUIDUtil$.MODULE$.toProto(id2))
            .build();
        storage.create(router);

        // Then both observers receive a notification.
        observer1.awaitOnNext(4, TIMEOUT);
        update =
            (ObjectNotification.Update) observer1.getOnNextEvents().get(3);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 4);
        assertUpdateEquals(update, id2, router, 0);

        observer2.awaitOnNext(3, TIMEOUT);
        update =
            (ObjectNotification.Update) observer2.getOnNextEvents().get(2);

        Assert.assertEquals(observer2.getOnNextEvents().size(), 3);
        assertUpdateEquals(update, id2, router, 0);

        // When deleting an object.
        storage.delete(Topology.Network.class, id1);

        // Then both observers receive a notification.
        observer1.awaitOnNext(5, TIMEOUT);
        update =
            (ObjectNotification.Update) observer1.getOnNextEvents().get(4);

        Assert.assertEquals(observer1.getOnNextEvents().size(), 5);
        assertDelete(update, Topology.Network.class, id1);

        observer2.awaitOnNext(4, TIMEOUT);
        update =
            (ObjectNotification.Update) observer2.getOnNextEvents().get(3);

        Assert.assertEquals(observer2.getOnNextEvents().size(), 4);
        assertDelete(update, Topology.Network.class, id1);

        // When a new observer subscribes.
        TestAwaitableObserver<ObjectNotification> observer3 =
            new TestAwaitableObserver<>();
        cache.observable().subscribe(observer3);

        // Then the observes receives a snapshot with the second object.
        observer3.awaitOnNext(1, TIMEOUT);
        snapshot =
            (ObjectNotification.Snapshot) observer3.getOnNextEvents().get(0);

        Assert.assertEquals(observer3.getOnNextEvents().size(), 1);
        Assert.assertEquals(snapshot.size(), 1);

        // When the first observer unsubscribes.
        sub1.unsubscribe();

        // And updating the object.
        router = router.toBuilder().setName("name").build();
        storage.update(router);

        // Then the second and third observers receive an update.
        observer2.awaitOnNext(5, TIMEOUT);
        update =
            (ObjectNotification.Update) observer2.getOnNextEvents().get(4);

        Assert.assertEquals(observer2.getOnNextEvents().size(), 5);
        assertUpdateEquals(update, id2, router, 1);

        observer3.awaitOnNext(2, TIMEOUT);
        update =
            (ObjectNotification.Update) observer3.getOnNextEvents().get(1);

        Assert.assertEquals(observer3.getOnNextEvents().size(), 2);
        assertUpdateEquals(update, id2, router, 1);

        // And the first observer does not receive future updates.
        Assert.assertEquals(observer1.getOnNextEvents().size(), 5);

        // When the cache is stopped.
        cache.stopAsync().awaitTerminated();

        // The remaining subscribers receive on completed.
        observer2.awaitCompletion(TIMEOUT);
        Assert.assertFalse(observer2.getOnCompletedEvents().isEmpty());

        observer3.awaitCompletion(TIMEOUT);
        Assert.assertFalse(observer3.getOnCompletedEvents().isEmpty());
    }

}
