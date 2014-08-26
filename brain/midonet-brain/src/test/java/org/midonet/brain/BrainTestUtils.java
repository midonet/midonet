/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.inject.Module;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.version.guice.VersionModule;

/**
 * Some utility classes to write tests in the Brain module.
 */
public class BrainTestUtils {

    public static String zkRoot = "/test/v3/midolman";

    /**
     * Fills the configuration with some default values for tests. Allows a
     * zkRoot to be defined by the user.
     */
    public static void fillTestConfig(HierarchicalConfiguration cfg) {
        cfg.setProperty("midolman.midolman_root_key", zkRoot);
        cfg.setProperty("cassandra.servers", "localhost:9171");
        cfg.addNodes(
            ZookeeperConfig.GROUP_NAME,
            Arrays.asList(
                new HierarchicalConfiguration.Node("midolman_root_key", zkRoot)
            )
        );
    }

    /**
     * Prepare the ZK directory.
     */
    public static void setupZkTestDirectory(Directory directory)
        throws InterruptedException, KeeperException
    {
        String[] nodes = zkRoot.split("/");
        String path = "/";

        for (String node : nodes) {
            if (!node.isEmpty()) {
                directory.add(path + node, null, CreateMode.PERSISTENT);
                path += node;
                path += "/";
            }
        }
        Setup.ensureZkDirectoryStructureExists(directory, zkRoot);
    }

    /**
     * A list of all the mocked modules necessary to run the top level
     * dependencies.
     */
    public static List<Module> modules(HierarchicalConfiguration config) {
        List<Module> modules = new ArrayList<>();
        // For VtepUpdaterTest
        modules.add(new VersionModule());  // For Comparator
        modules.add(new SerializationModule());  // For Serializer
        modules.add(new TypedConfigModule<>(MidolmanConfig.class));
        modules.add(new ConfigProviderModule(config)); // For ConfigProvider
        // Directory and Reactor
        modules.add(new MockZookeeperConnectionModule());
        modules.add(new ClusterClientModule());
        return modules;
    }

}
