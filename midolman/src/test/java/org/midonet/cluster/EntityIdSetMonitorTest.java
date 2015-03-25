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

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;
import org.midonet.cluster.storage.MidonetBackendTestModule;
import rx.Observable;

import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.version.DataWriteVersion;

import static org.midonet.cluster.EntityMonitorTest.Accumulator;
import static org.midonet.cluster.EntityMonitorTest.Actions;
import static org.midonet.cluster.EntityMonitorTest.TestEntityConfig;
import static org.midonet.cluster.EntityMonitorTest.TestEntityZkManager;
import static org.midonet.cluster.EntityMonitorTest.TestModule;

public class EntityIdSetMonitorTest {

    Injector injector = null;
    TestEntityZkManager teZkMgr = null;
    ZookeeperConnectionWatcher zkConnWatcher = null;

    @Before
    public void setup() throws Exception {
        injector = Guice.createInjector(
            new SerializationModule(),
            MidonetBackendTestModule.apply(),
            new MidolmanConfigModule(MidoTestConfigurator.forAgents()),
            new MockZookeeperConnectionModule(),
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
    public void testCreateAndDelete() throws Exception {

        // Element 1
        UUID id1 = UUID.randomUUID();
        TestEntityConfig cfg1 = new TestEntityConfig();
        cfg1.setName("name_" + id1);

        // Element 2
        UUID id2 = UUID.randomUUID();
        TestEntityConfig cfg2 = new TestEntityConfig();
        cfg2.setName("name_" + id2);

        EntityIdSetMonitor<UUID> m = new EntityIdSetMonitor<>(teZkMgr,
                                                              zkConnWatcher);
        Observable<EntityIdSetEvent<UUID>> updates = m.getObservable();

        Accumulator<EntityIdSetEvent<UUID>> expectedUpdates =
            Accumulator.exactly(
                EntityIdSetEvent.create(id1),
                EntityIdSetEvent.create(id2),
                EntityIdSetEvent.delete(id1)
            );

        updates.subscribe(
            expectedUpdates,
            Actions.failAny(Throwable.class),
            Actions.failAny()
        );

        teZkMgr.create(id1, cfg1);
        teZkMgr.create(id2, cfg2);
        teZkMgr.delete(id1);

        expectedUpdates.verify();
    }

    @Test
    public void testState() throws Exception {

        // Element 1
        UUID id1 = UUID.randomUUID();
        TestEntityConfig cfg1 = new TestEntityConfig();
        cfg1.setName("name_" + id1);

        // Element 2
        UUID id2 = UUID.randomUUID();
        TestEntityConfig cfg2 = new TestEntityConfig();
        cfg2.setName("name_" + id2);

        HashSet<UUID> ids = new HashSet<>();
        ids.add(id1);
        ids.add(id2);

        teZkMgr.create(id1, cfg1);
        teZkMgr.create(id2, cfg2);

        List<EntityIdSetEvent<UUID>> events = EntityIdSetEvent.state(ids);
        Accumulator<EntityIdSetEvent<UUID>> expectedUpdates =
            Accumulator.exactly(events);

        EntityIdSetMonitor<UUID> m = new EntityIdSetMonitor<>(teZkMgr,
                                                              zkConnWatcher);
        Observable<EntityIdSetEvent<UUID>> updates = m.getObservable();

        updates.subscribe(
            expectedUpdates,
            Actions.failAny(Throwable.class),
            Actions.failAny()
        );

        m.notifyState();

        expectedUpdates.verifyIgnoreOrder();
    }
}
