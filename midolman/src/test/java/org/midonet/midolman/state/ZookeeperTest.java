/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.PrivateModule;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.cluster.DataClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.version.guice.VersionModule;

public abstract class ZookeeperTest {

    // Zookeeper configurations
    protected static TestingServer server;
    protected static final int ZK_PORT = 12181;

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
                                "midolman_root_key", zkRoot)));
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
                new DataClientModule())
        );

        modules.addAll(getExtraModules());
        return modules;
    }

    @BeforeClass
    public static void classSetUp() throws Exception {
        server = new TestingServer(ZK_PORT);
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        server.close();
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
    }
}
