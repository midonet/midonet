/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

import org.midonet.cluster.data.Entity;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.zkManagers.BaseConfig;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EntityMonitorTest {

    public static class TestModule extends AbstractModule {

        String basePath = "/test";
        @Override
        public void configure() {
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
            bind(Reactor.class).toInstance(new MockReactor());
            bind(TestEntityZkManager.class).asEagerSingleton();
        }

        @Provides
        @Singleton
        public ZkManager provideZkManager(Directory directory) {
            return new ZkManager(directory, basePath);
        }

    }

    public static class TestEntity
           extends Entity.Base<UUID, TestEntity.Data, TestEntity> {
        public static class Data {
            public String name;
            @Override
            public int hashCode() { return Objects.hash(name); }
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Data that = (Data) o;
                return that.name.equals(name);
            }
        }
        public TestEntity(UUID id) { this(id, new Data()); }
        public TestEntity(UUID id, Data data) {
            super(id, data);
        }
        @Override protected TestEntity self() { return this; }
    }

    public static class TestEntityConfig extends BaseConfig {
        private String name;
        public TestEntityConfig() { super(); }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        @Override
        public boolean equals(Object o) {
            if (this == o) { return true; }
            if (o == null || getClass() != o.getClass()) { return false; }
            TestEntityConfig that = (TestEntityConfig) o;
            return Objects.equals(this.name, that.name);
        }
        @Override
        public int hashCode() { return Objects.hash(name); }
    }

    public static class TestEntityZkManager
        extends AbstractZkManager<UUID, TestEntityConfig>
        implements WatchableZkManager<UUID, TestEntityConfig> {

        public final String entitiesPath;

        @Inject
        public TestEntityZkManager(ZkManager zk, PathBuilder paths,
                                   Serializer serializer) {
            super(zk, paths, serializer);
            this.entitiesPath = paths.getBasePath() + "/testentity";
        }

        @Override
        protected String getConfigPath(UUID key) {
            return entitiesPath + "/" + key;
        }

        @Override
        protected Class<TestEntityConfig> getConfigClass() {
            return TestEntityConfig.class;
        }

        @Override
        public List<UUID> getAndWatchIdList(Runnable watcher)
            throws StateAccessException {
            return getUuidList(entitiesPath, watcher);
        }

        public void create(UUID id, TestEntityConfig cfg)
            throws SerializationException, StateAccessException {
            List<Op> ops = new ArrayList<>();
            ops.add(simpleCreateOp(id, cfg));
            zk.multi(ops);
        }

        public void update(UUID id, TestEntityConfig cfg)
            throws SerializationException, StateAccessException {
            List<Op> ops = new ArrayList<>();
            ops.add(simpleUpdateOp(id, cfg));
            zk.multi(ops);
        }

        public void delete(UUID id)
            throws SerializationException, StateAccessException {
                zk.delete(getConfigPath(id));
        }
    }

    public static final EntityMonitor.Transformer<UUID, TestEntityConfig,
            TestEntity> transformer =
        new EntityMonitor.Transformer<UUID, TestEntityConfig, TestEntity>() {
            @Override
            public TestEntity transform(UUID id, TestEntityConfig data) {
                TestEntity e = new TestEntity(id);
                e.getData().name = data.name;
                return e;
            }
        };


    EntityMonitor<UUID, TestEntityConfig, TestEntity> em = null;
    Injector injector = null;
    TestEntityZkManager teZkMgr = null;

    @Before
    public void setup() throws Exception {
        injector = Guice.createInjector(
            new VersionModule(),
            new SerializationModule(),
            new ConfigProviderModule(new HierarchicalConfiguration()),
            new MockZookeeperConnectionModule(),
            new TypedConfigModule<>(MidolmanConfig.class),
            new TestModule());

        PathBuilder paths = injector.getInstance(PathBuilder.class);
        Directory directory = injector.getInstance(Directory.class);
        teZkMgr = injector.getInstance(TestEntityZkManager.class);

        directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
        directory.add(paths.getWriteVersionPath(),
                      DataWriteVersion.CURRENT.getBytes(),
                      CreateMode.PERSISTENT);
        directory.add(teZkMgr.entitiesPath,
                      DataWriteVersion.CURRENT.getBytes(),
                      CreateMode.PERSISTENT);

        ZookeeperConnectionWatcher zkConnWatcher =
            new ZookeeperConnectionWatcher();
        em = new EntityMonitor<>(teZkMgr, zkConnWatcher, transformer);
    }

    public static class Actions {
        public static <T> Action1<T> failAny(Class<T> k) {
            return new Action1<T>() {
                @Override public void call(T t) { fail("Unexpected: " + t); }
            };
        }
        public static Action0 failAny() {
            return new Action0() {
                @Override public void call() { fail("Unexpected"); }
            };
        }
    }

    public static class Accumulator<T> implements Action1<T> {
        private final List<T> expected;
        private final List<T> found = new ArrayList<>();
        @SafeVarargs
        public static <T> Accumulator<T> exactly(T... items) {
            return new Accumulator<>(Arrays.asList(items));
        }

        public static <T> Accumulator<T> exactly(List<T> items) {
            return new Accumulator<>(items);
        }

        private Accumulator(List<T> items) {
            expected = new ArrayList<>(items);
        }

        @Override
        public void call(T t) { found.add(t); }
        public void verify() { assertEquals(expected, found); }
        public void verifyIgnoreOrder() {
            assertTrue(expected.containsAll(found) &&
                       expected.size() == found.size());
        }
    }

    /*
     * Adds two elements and watches them, then modifies one. We expect to
     * receive the appropriate notifications.
     */
    @Test
    public void test() throws Exception {

        // Ids
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        // Element 1
        TestEntityConfig cfg1 = new TestEntityConfig();
        cfg1.setName("name_" + id1);

        // Element 1, version 2
        TestEntityConfig cfg1_2 = new TestEntityConfig();
        cfg1_2.setName("new_name_" + id1);

        // Element 2
        TestEntityConfig cfg2 = new TestEntityConfig();
        cfg2.setName("name_" + id2);

        TestEntity e1 = transformer.transform(id1, cfg1);
        TestEntity e1_2 = transformer.transform(id1, cfg1_2);
        TestEntity e2 = transformer.transform(id2, cfg2);

        teZkMgr.create(id1, cfg1);
        teZkMgr.create(id2, cfg2);

        Observable<TestEntity> obs = em.updated();
        assertNotNull(obs);

        Accumulator<TestEntity> acc = Accumulator.exactly(e1, e2, e1_2);
        obs.subscribe(acc,
                      Actions.failAny(Throwable.class),
                      Actions.failAny());

        em.watch(id1);
        em.watch(id2);

        teZkMgr.update(id1, cfg1_2);

        acc.verify();
    }

}
