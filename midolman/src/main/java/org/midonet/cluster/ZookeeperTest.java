/*
 * Copyright 2015 Midokura SARL
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.name.Names;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;

import org.midonet.cluster.services.LegacyStorageService;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendModule;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.SessionUnawareConnectionWatcher;
import org.midonet.util.eventloop.Reactor;

import static org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider.DIRECTORY_REACTOR_TAG;

/**
 * This class provides basic ZK bootstrapping for testing ZK servers, assuming
 * that the testing server is started once for all the Neutron tests that
 * rely on Curator test server.  This is certainly undesirable since multiple
 * test classes will end up sharing one test ZK server.  However, each test is
 * performed under a different root directory.  Also, this is
 * temporary.  Once ZkConnection is replaced by Curator, we can remove
 * this check, and stop/start the server on class teardown/setup.
 *
 * Note that because this class is shared by tests in different modules, we
 * it on the main tree instead of tests.  In order to avoid introducing
 * dependencies on junit or the zk testing server, implementations should take
 * care of setting up their own ZK server on the ZK_PORT provided by this class
 */
public abstract class ZookeeperTest {

    protected static final int ZK_PORT = (int)(Math.random() * 1000) + 64000;

    protected Injector injector;
    private String zkRoot;

    private static Config getConfig(String zkRoot) {
        scala.Option<String> None = scala.Option.apply(null);
        Config config = MidoNodeConfigurator.bootstrapConfig(None);
        config = config.withValue("zookeeper.root_key",
            ConfigValueFactory.fromAnyRef(zkRoot));
        config = config.withValue("zookeeper.use_new_stack",
            ConfigValueFactory.fromAnyRef(true));
        config = config.withValue("zookeeper.zookeeper_hosts",
            ConfigValueFactory.fromAnyRef("127.0.0.1:" + ZK_PORT));
        return config;
    }

    protected Directory getDirectory() {
        return injector.getInstance(Directory.class);
    }

    protected PathBuilder getPathBuilder() {
        return injector.getInstance(PathBuilder.class);
    }

    protected List<PrivateModule> getExtraModules() {
        return new ArrayList<>();
    }

    protected String getPath(String relPath) {
        return zkRoot + relPath;
    }

    private List<PrivateModule> getDepModules() {
        Config conf = MidoTestConfigurator.forAgents(getConfig(zkRoot));
        MidolmanConfig config = MidolmanConfigModule.createConfig(conf);

        List<PrivateModule> modules = new ArrayList<>();
        modules.addAll(
            Arrays.asList(
                new SerializationModule(),
                new MidolmanConfigModule(config),
                new MidonetBackendModule(),
                new ZookeeperConnectionModule(SessionUnawareConnectionWatcher.class),
                new LegacyClusterModule())
        );

        modules.addAll(getExtraModules());
        return modules;
    }

    public void setUp() throws Exception {

        // Run the test on a new directory
        zkRoot = "/test_" + UUID.randomUUID();
        injector = Guice.createInjector(getDepModules());
        injector.getInstance(LegacyStorageService.class)
                .startAsync()
                .awaitRunning();
        injector.getInstance(MidonetBackend.class)
                .startAsync()
                .awaitRunning();
    }

    public void tearDown() throws Exception {
        injector.getInstance(MidonetBackend.class)
                .stopAsync()
                .awaitTerminated();
        injector.getInstance(LegacyStorageService.class)
                .stopAsync()
                .awaitTerminated();
        injector.getInstance(Directory.class).closeConnection();
        injector.getInstance(Key.get(Reactor.class,
                                     Names.named(DIRECTORY_REACTOR_TAG)))
                .shutDownNow();
    }
}
