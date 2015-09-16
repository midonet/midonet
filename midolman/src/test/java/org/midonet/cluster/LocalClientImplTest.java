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

import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.storage.MidonetBackendTestModule;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.ArpCache;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;

public class LocalClientImplTest {

    @Inject
    Client client;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";


    Config fillConfig() {
        return ConfigFactory.empty().withValue("zookeeper.root_key",
                                               ConfigValueFactory
                                                   .fromAnyRef(zkRoot));
    }

    RouterZkManager getRouterZkManager() {
        return injector.getInstance(RouterZkManager.class);
    }

    Directory zkDir() {
        return injector.getInstance(Directory.class);
    }

    @Before
    public void initialize() {
        Config conf = MidoTestConfigurator.forAgents(fillConfig());
        injector = Guice.createInjector(
            new SerializationModule(),
            new MidolmanConfigModule(conf),
            new MockZookeeperConnectionModule(),
            new MidonetBackendTestModule(conf),
            new LegacyClusterModule()
        );
        injector.injectMembers(this);

    }

    @Test
    public void ArpCacheTest() throws InterruptedException, KeeperException,
            StateAccessException, SerializationException {
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        UUID routerId = getRouterZkManager().create();
        TestRouterBuilder routerBuilder = new TestRouterBuilder();
        client.getRouter(routerId, routerBuilder);

        pollCallCounts(routerBuilder, 1);
        assertThat("Build is called", routerBuilder.getBuildCallsCount(),
                   equalTo(1));

        IPv4Addr ipAddr = IPv4Addr.fromString("192.168.0.0");
        ArpCacheEntry arpEntry = new ArpCacheEntry(MAC.random(), 0, 0, 0);
        // add an entry in the arp cache.
        routerBuilder.addNewArpEntry(ipAddr, arpEntry);

        pollCallCounts(routerBuilder, 1);
        assertEquals(arpEntry, routerBuilder.getArpEntryForIp(ipAddr));
        assertThat("Router update was notified",
                   routerBuilder.getBuildCallsCount(), equalTo(1));

    }

    class TestRouterBuilder implements RouterBuilder, BuildCallCounter {
        int buildCallsCount = 0;
        ArpCache arpCache;
        UUID loadBalancerId;

        public void addNewArpEntry(IPv4Addr ipAddr, ArpCacheEntry entry) {
            arpCache.add(ipAddr, entry);
        }

        public ArpCacheEntry getArpEntryForIp(IPv4Addr ipAddr) {
            return arpCache.get(ipAddr);
        }

        public int getBuildCallsCount() {
            return buildCallsCount;
        }

        @Override
        public RouterBuilder setAdminStateUp(boolean adminStateUp) {
            return this;

        }

        @Override
        public RouterBuilder setInFilter(UUID filterID) {
            return this;
        }

        @Override
        public RouterBuilder setOutFilter(UUID filterID) {
            return this;
        }

        @Override
        public void setLoadBalancer(UUID lbID) {
            loadBalancerId = lbID;
        }

        @Override
        public void build() {
            buildCallsCount++;
        }

        @Override
        public void setArpCache(ArpCache table) {
           arpCache = table;
        }

        @Override
        public void addRoute(Route rt) {
            // TODO Auto-generated method stub

        }

        @Override
        public void removeRoute(Route rt) {
            // TODO Auto-generated method stub

        }
    }

    interface BuildCallCounter {
        int getBuildCallsCount();
    }

    public static void pollCallCounts(BuildCallCounter bldr, int nCalls)
            throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            if (bldr.getBuildCallsCount() == nCalls)
                break;
        }
    }
}
