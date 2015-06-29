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

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.storage.MidonetBackendTestModule;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.state.Directory;
import org.midonet.packets.IPv4Subnet;

public class LocalDataClientImplTestBase {

    @Inject protected DataClient client;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";


    Config fillConfig() {
        return ConfigFactory.empty().withValue("zookeeper.root_key",
                ConfigValueFactory.fromAnyRef(zkRoot));
    }


    Directory zkDir() {
        return injector.getInstance(Directory.class);
    }

    @Before
    public void initialize() throws InterruptedException, KeeperException {
        Config conf = MidoTestConfigurator.forAgents(fillConfig());
        injector = Guice.createInjector(
                new SerializationModule(),
                new MidonetBackendTestModule(),
                new MidolmanConfigModule(conf),
                new MockZookeeperConnectionModule(),
                new LegacyClusterModule()
        );
        injector.injectMembers(this);
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
    }

    protected Bridge getStockBridge() {
        return new Bridge()
                .setAdminStateUp(true);
    }

    protected Subnet getStockSubnet(String cidr) {
        return new Subnet().setSubnetAddr(IPv4Subnet.fromCidr(cidr));
    }

}

