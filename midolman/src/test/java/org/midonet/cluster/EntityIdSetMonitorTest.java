/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;

import static org.midonet.cluster.EntityMonitorTest.TestEntityConfig;
import static org.midonet.cluster.EntityMonitorTest.TestEntityZkManager;
import static org.midonet.cluster.EntityMonitorTest.TestModule;
import static org.midonet.cluster.EntityMonitorTest.Accumulator;
import static org.midonet.cluster.EntityMonitorTest.Actions;

public class EntityIdSetMonitorTest {

    Injector injector = null;
    TestEntityZkManager teZkMgr = null;
    ZookeeperConnectionWatcher zkConnWatcher = null;

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

        zkConnWatcher = new ZookeeperConnectionWatcher();
    }

    @Test
    public void test() throws Exception {

        // Element 1
        UUID id1 = UUID.randomUUID();
        TestEntityConfig cfg1 =
            new TestEntityConfig();
        cfg1.setName("name_" + id1);

        // Element 2
        UUID id2 = UUID.randomUUID();
        TestEntityConfig cfg2 =
            new TestEntityConfig();
        cfg2.setName("name_" + id2);

        EntityIdSetMonitor m = new EntityIdSetMonitor(teZkMgr, zkConnWatcher);
        Observable<UUID> created = m.created();
        Observable<UUID> deleted = m.deleted();

        Accumulator<UUID> expectCreated = Accumulator.exactly(id1, id2);
        Accumulator<UUID> expectRemoved = Accumulator.exactly(id1);
        created.subscribe(
            expectCreated,
            Actions.failAny(Throwable.class),
            Actions.failAny()
        );

        deleted.subscribe(
            expectRemoved,
            Actions.failAny(Throwable.class),
            Actions.failAny()
        );

        teZkMgr.create(id1, cfg1);
        teZkMgr.create(id2, cfg2);
        teZkMgr.delete(id1);

        expectCreated.verify();
        expectRemoved.verify();
    }

}
