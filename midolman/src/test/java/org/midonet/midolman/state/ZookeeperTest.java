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
package org.midonet.midolman.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.name.Names;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.guice.cluster.DataClientModule;
import org.midonet.midolman.guice.cluster.MidostoreModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.util.eventloop.Reactor;

public abstract class ZookeeperTest {

    // Zookeeper configurations
    protected static TestingServer server;
    protected static final int ZK_PORT = (int) (Math.random() * 50000) + 10000;

    protected Injector injector;
    private String zkRoot;

    private MidostoreSetupService getMidostoreService() {
        return injector.getInstance(MidostoreSetupService.class);
    }

    private static HierarchicalConfiguration getConfig(String zkRoot) {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                        Arrays.asList(
                            new HierarchicalConfiguration.Node(
                                "curator_enabled", true)));
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                        Arrays.asList(
                            new HierarchicalConfiguration.Node(
                                "midolman_root_key", zkRoot)));
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                        Arrays.asList(
                            new HierarchicalConfiguration.Node(
                                "zookeeper_hosts", "127.0.0.1:" + ZK_PORT)));

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

        List<PrivateModule> modules = new ArrayList<>();
        modules.addAll(
            Arrays.asList(
                new VersionModule(),
                new SerializationModule(),
                new ConfigProviderModule(getConfig(zkRoot)),
                new ZookeeperConnectionModule(),
                new MidostoreModule(),
                new DataClientModule())
        );

        modules.addAll(getExtraModules());
        return modules;
    }

    @BeforeClass
    public static void classSetUp() throws Exception {
        // Start once for all the Neutron tests that rely on Curator test
        // server.  This is certainly undesirable since multiple test classes
        // will end up sharing one test ZK server.  However, each test is
        // performed under a different root directory.  Also, this is
        // temporary.  Once ZkConnection is replaced by Curator, we can remove
        // this check, and stop/start the server on class teardown/setup.
        // TODO(RYU): Remove this check when Curator is fully integrated
        if (server == null) {
            server = new TestingServer(ZK_PORT);
        }
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Before
    public void setUp() throws Exception {

        // Run the test on a new directory
        zkRoot = "/test_" + UUID.randomUUID();
        injector = Guice.createInjector(getDepModules());
        getMidostoreService().startAsync().awaitRunning();

    }

    @After
    public void tearDown() throws Exception {
        getMidostoreService().stopAsync().awaitTerminated();
        injector.getInstance(Directory.class).closeConnection();
        injector.getInstance(Key.get(Reactor.class,
                Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)))
            .shutDownNow();
    }
}
