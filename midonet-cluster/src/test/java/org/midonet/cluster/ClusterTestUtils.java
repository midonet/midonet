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

import java.util.ArrayList;
import java.util.List;

import com.google.inject.Module;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.midonet.cluster.storage.MidonetBackendTestModule;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.state.Directory;

/**
 * Some utility classes to write tests in the Brain module.
 */
public class ClusterTestUtils {

    public static String zkRoot = "/midonet";

    /**
     * Fills the configuration with some default values for tests. Allows a
     * zkRoot to be defined by the user.
     */
    private static Config fillTestConfig(Config cfg) {
        Config ret = cfg.withValue("zookeeper.root_key", ConfigValueFactory.fromAnyRef(zkRoot));
        return ret.withValue("cassandra.servers", ConfigValueFactory.fromAnyRef("localhost:9171"));
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
    public static List<Module> modules(Config config) {
        Config newConf = fillTestConfig(config).withFallback(MidoTestConfigurator.forAgents());
        List<Module> modules = new ArrayList<>();
        // For VtepUpdaterTest
        modules.add(new SerializationModule());  // For Serializer
        modules.add(new MidonetBackendTestModule(newConf));
        modules.add(new MidolmanConfigModule(newConf));
        // Directory and Reactor
        modules.add(new MockZookeeperConnectionModule());
        modules.add(new LegacyClusterModule());
        return modules;
    }

    public static List<Module> modules() {
        return modules(ConfigFactory.empty());
    }

}
