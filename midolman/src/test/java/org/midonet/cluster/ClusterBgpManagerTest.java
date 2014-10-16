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

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.client.BGPListBuilder;
import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Converter;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.BgpZkManager;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ClusterBgpManagerTest {
    private ClusterBgpManager clusterBgpManager;
    private BgpZkManager bgpMgr;
    private UUID portId;
    private int actualCalls;
    private int expectedCalls;

    public class TestModule extends AbstractModule {
        String basePath = "/midolman";

        @Override
        protected void configure() {
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
            bind(Reactor.class).toInstance(new MockReactor());
            bind(ClusterBgpManager.class).asEagerSingleton();
        }

        @Provides @Singleton
        public ZkManager provideZkManager(Directory directory) {
            return new ZkManager(directory, basePath);
        }

        @Provides @Singleton
        public BgpZkManager provideClusterBGPManager(ZkManager zkManager,
                                                     PathBuilder builder,
                                                     Serializer serializer) {
            return new BgpZkManager(zkManager, builder, serializer);
        }

        @Provides @Singleton
        public AdRouteZkManager provideAdRouteZkManager(ZkManager zkManager,
                                                        PathBuilder builder,
                                                        Serializer serializer) {
            return new AdRouteZkManager(zkManager, builder, serializer);
        }
    }

    public class FailingBGPListBuilder implements BGPListBuilder {
        @Override
        public void addBGP(BGP bgp) {
            fail();
        }

        @Override
        public void removeBGP(UUID bgpID) {
            fail();
        }

        @Override
        public void addAdvertisedRoute(AdRoute adRoute) {
            fail();
        }

        @Override
        public void removeAdvertisedRoute(AdRoute adRoute) {
            fail();
        }

        @Override
        public void updateBGP(BGP bgp) {
            fail();
        }

        public BGPListBuilder expectCalls(int numCalls) {
            expectedCalls = numCalls;
            return new BGPListBuilder() {
                @Override
                public void addBGP(BGP bgp) {
                    actualCalls += 1;
                    FailingBGPListBuilder.this.addBGP(bgp);
                }

                @Override
                public void removeBGP(UUID bgpID) {
                    actualCalls += 1;
                    FailingBGPListBuilder.this.removeBGP(bgpID);
                }

                @Override
                public void addAdvertisedRoute(AdRoute adRoute) {
                    actualCalls += 1;
                    FailingBGPListBuilder.this.addAdvertisedRoute(adRoute);
                }

                @Override
                public void removeAdvertisedRoute(AdRoute adRoute) {
                    actualCalls += 1;
                    FailingBGPListBuilder.this.removeAdvertisedRoute(adRoute);
                }

                @Override
                public void updateBGP(BGP bgp) {
                    actualCalls += 1;
                    FailingBGPListBuilder.this.updateBGP(bgp);
                }
            };
        }
    }

    @Before
    public void setup() throws UnknownHostException,
                               KeeperException, InterruptedException {
        Injector injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new ConfigProviderModule(new HierarchicalConfiguration()),
                new MockZookeeperConnectionModule(),
                new TypedConfigModule<>(MidolmanConfig.class),
                new TestModule());

        PathBuilder paths = injector.getInstance(PathBuilder.class);
        Directory directory = injector.getInstance(Directory.class);
        directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
        directory.add(paths.getWriteVersionPath(),
                DataWriteVersion.CURRENT.getBytes(),
                CreateMode.PERSISTENT);
        directory.add(paths.getHostsPath(), null, CreateMode.PERSISTENT);
        directory.add(paths.getBgpPath(), null, CreateMode.PERSISTENT);
        directory.add(paths.getAdRoutesPath(), null, CreateMode.PERSISTENT);
        directory.add(paths.getPortsPath(), null, CreateMode.PERSISTENT);
        bgpMgr = injector.getInstance(BgpZkManager.class);
        clusterBgpManager = injector.getInstance(ClusterBgpManager.class);

        portId = UUID.randomUUID();
        directory.add(paths.getPortPath(portId), null, CreateMode.PERSISTENT);
        directory.add(paths.getPortBgpPath(portId), null, CreateMode.PERSISTENT);
    }

    @After
    public void after() throws Throwable {
        assertEquals(expectedCalls, actualCalls);
    }

    @Test
    public void testAddsBGP() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }
        }.expectCalls(1));
    }

    @Test
    public void testRegisterBuilderForUnknownBGP() throws Throwable {
        clusterBgpManager.registerNewBuilder(portId,
                new FailingBGPListBuilder().expectCalls(0));
    }

    @Test
    public void testRegisterBuilderMultipleTimes() throws Throwable {
        clusterBgpManager.registerNewBuilder(portId,
                new FailingBGPListBuilder().expectCalls(0));
        clusterBgpManager.registerNewBuilder(portId,
                new FailingBGPListBuilder().expectCalls(0));
    }

    @Test
    public void testUpdateBGP() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        final IPv4Addr newIp = IPv4Addr.fromString("192.168.1.2");

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }

            @Override
            public void updateBGP(BGP bgp) {
                assertEquals(bgp.getPeerAddr(), newIp);
            }
        }.expectCalls(2));

        myBgp.setPeerAddr(newIp);
        bgpMgr.update(bgpId, myBgp);
    }

    @Test
    public void testRemoveBGP() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }

            @Override
            public void removeBGP(UUID bgpID) {
                assertEquals(myBgp.getId(), bgpID);
            }
        }.expectCalls(2));

        bgpMgr.delete(bgpId);
    }

    @Test
    public void testAddAnotherBGP() throws Throwable {
        final BGP[] myBgp = new BGP[] {
                new BGP()
                    .setPortId(portId)
                    .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                    .setLocalAS(1)
                    .setPeerAS(2)
        };
        bgpMgr.create(myBgp[0]);

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                bgp.setId(null);
                assertEquals(myBgp[0], bgp);
            }
        }.expectCalls(2));

        myBgp[0] = new BGP()
                    .setPortId(portId)
                    .setPeerAddr(IPv4Addr.fromString("192.168.1.2"))
                    .setLocalAS(3)
                    .setPeerAS(4);
        bgpMgr.create(myBgp[0]);
    }

    @Test
    public void testAddAnotherAndRemoveBGP() throws Throwable {
        final BGP[] myBgp = new BGP[] {
                new BGP()
                    .setPortId(portId)
                    .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                    .setLocalAS(1)
                    .setPeerAS(2)
        };
        bgpMgr.create(myBgp[0]);

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                bgp.setId(null);
                assertEquals(myBgp[0], bgp);
            }

            @Override
            public void removeBGP(UUID bgpID) {
                assertEquals(myBgp[0].getId(), bgpID);
            }
        }.expectCalls(3));

        myBgp[0] = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.2"))
                .setLocalAS(3)
                .setPeerAS(4);
        myBgp[0].setId(bgpMgr.create(myBgp[0]));

        bgpMgr.delete(myBgp[0].getId());
    }

    @Test
    public void testAddRoute() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        final AdRoute route = new AdRoute()
                .setPrefixLength((byte) 24)
                .setNwPrefix(InetAddress.getByAddress(
                                    IPv4Addr.stringToBytes("192.168.1.2")))
                .setBgpId(bgpId);
        UUID routeId = clusterBgpManager.adRouteMgr.create(
                                            Converter.toAdRouteConfig(route));
        route.setId(routeId);

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }

            @Override
            public void addAdvertisedRoute(AdRoute adRoute) {
                assertEquals(route, adRoute);
            }
        }.expectCalls(2));
    }

    @Test
    public void testRemoveRoute() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        final AdRoute route = new AdRoute()
                .setPrefixLength((byte) 24)
                .setNwPrefix(InetAddress.getByAddress(
                        IPv4Addr.stringToBytes("192.168.1.2")))
                .setBgpId(bgpId);
        UUID routeId = clusterBgpManager.adRouteMgr.create(
                Converter.toAdRouteConfig(route));
        route.setId(routeId);

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }

            @Override
            public void addAdvertisedRoute(AdRoute adRoute) {
                assertEquals(route, adRoute);
            }

            @Override
            public void removeAdvertisedRoute(AdRoute adRoute) {
                assertEquals(route, adRoute);
            }
        }.expectCalls(3));

        clusterBgpManager.adRouteMgr.delete(routeId);
    }

    @Test
    public void testAddAnotherRoute() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        final AdRoute[] route = new AdRoute[] { new AdRoute()
                .setPrefixLength((byte) 24)
                .setNwPrefix(InetAddress.getByAddress(
                        IPv4Addr.stringToBytes("192.168.1.2")))
                .setBgpId(bgpId) };
        clusterBgpManager.adRouteMgr.create(
                Converter.toAdRouteConfig(route[0]));

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }

            @Override
            public void addAdvertisedRoute(AdRoute adRoute) {
                adRoute.setId(null);
                assertEquals(route[0], adRoute);
            }
        }.expectCalls(3));

        route[0] = new AdRoute()
                .setPrefixLength((byte) 24)
                .setNwPrefix(InetAddress.getByAddress(
                        IPv4Addr.stringToBytes("192.168.1.3")))
                .setBgpId(bgpId);
        clusterBgpManager.adRouteMgr.create(
                Converter.toAdRouteConfig(route[0]));
    }

    @Test
    public void testAddAnotherAndRemoveRoute() throws Throwable {
        final BGP myBgp = new BGP()
                .setPortId(portId)
                .setPeerAddr(IPv4Addr.fromString("192.168.1.1"))
                .setLocalAS(1)
                .setPeerAS(2);
        UUID bgpId = bgpMgr.create(myBgp);
        myBgp.setId(bgpId);

        final AdRoute[] route = new AdRoute[] { new AdRoute()
                .setPrefixLength((byte) 24)
                .setNwPrefix(InetAddress.getByAddress(
                        IPv4Addr.stringToBytes("192.168.1.2")))
                .setBgpId(bgpId) };
        clusterBgpManager.adRouteMgr.create(
                Converter.toAdRouteConfig(route[0]));

        clusterBgpManager.registerNewBuilder(portId, new FailingBGPListBuilder() {
            @Override
            public void addBGP(BGP bgp) {
                assertEquals(myBgp, bgp);
            }

            @Override
            public void addAdvertisedRoute(AdRoute adRoute) {
                adRoute.setId(null);
                assertEquals(route[0], adRoute);
            }

            @Override
            public void removeAdvertisedRoute(AdRoute adRoute) {
                assertEquals(route[0], adRoute);
            }
        }.expectCalls(4));

        route[0] = new AdRoute()
                .setPrefixLength((byte) 24)
                .setNwPrefix(InetAddress.getByAddress(
                        IPv4Addr.stringToBytes("192.168.1.3")))
                .setBgpId(bgpId);
        route[0].setId(clusterBgpManager.adRouteMgr.create(
                Converter.toAdRouteConfig(route[0])));

        clusterBgpManager.adRouteMgr.delete(route[0].getId());
    }
}
