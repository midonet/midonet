/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestNatLeaseManager {

    private Injector injector;

    public class TestModule extends AbstractModule {

        private final String basePath;

        public TestModule(String basePath) {
            this.basePath = basePath;
        }

        @Override
        protected void configure() {
            bind(Reactor.class).toInstance(new MockReactor());
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
        }

        @Provides @Singleton
        public Directory provideDirectory(PathBuilder paths) {
            Directory directory = new MockDirectory();
            try {
                directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
                directory.add(paths.getWriteVersionPath(),
                        DataWriteVersion.CURRENT.getBytes(),
                        CreateMode.PERSISTENT);
                directory.add(paths.getRoutersPath(), null,
                        CreateMode.PERSISTENT);
                directory.add(paths.getFiltersPath(), null,
                        CreateMode.PERSISTENT);
            } catch (Exception ex) {
                throw new RuntimeException("Could not initialize zk", ex);
            }
            return directory;
        }

        @Provides @Singleton
        public ZkManager provideZkManager(Directory directory) {
            return new ZkManager(directory, basePath);
        }

        @Provides @Singleton
        public RouterZkManager provideRouterZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
            return new RouterZkManager(zkManager, paths, serializer);
        }

        @Provides @Singleton
        public FiltersZkManager provideFiltersZkManager(ZkManager zkManager,
                                                        PathBuilder paths,
                                                        Serializer serializer) {
            return new FiltersZkManager(zkManager, paths, serializer);
        }
    }

    @Before
    public void setUp() throws Exception {
        String basePath = "/midonet";
        injector = Guice.createInjector(
                new TestModule(basePath),
                new VersionModule(),
                new SerializationModule()
        );
    }

    /*
    @Test
    public void testSnatPingIPv4() {
        List<NatTarget> nats = new ArrayList<NatTarget>();
        nats.add(new NatTarget(0xc0a8010b, 0xc0a8010b, (short) 100, (short) 100));
        nats.add(new NatTarget(0xc0a8010c, 0xc0a8010c, (short) 200, (short) 200));
        IPv4Addr nwDst = new IPv4Addr(0xc0a80101);
        IPv4Addr oldNwSrc = new IPv4Addr(0x0a000002);
        testSnatPing(nats, nwDst, oldNwSrc);
    }

    private <T extends IPAddr> void testSnatPing(List<NatTarget> nats, T nwDst,
                                                 T oldNwSrc) {
        Set<NatTarget> natSet = new HashSet<NatTarget>();
        int i = 0;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            short oldTpSrc = (short) (10 * i); // this would be icmp identifier
            short tpDst = oldTpSrc;            // in ICMP the port is redundant
            Assert.assertNull(natManager.lookupSnatFwd(ICMP.PROTOCOL_NUMBER,
                                                       oldNwSrc, oldTpSrc,
                                                       nwDst, tpDst));
            NwTpPair pair = natManager.allocateSnat(ICMP.PROTOCOL_NUMBER,
                                                       oldNwSrc, oldTpSrc,
                                                       nwDst, tpDst, natSet);
            assertEquals(nat.nwStart, pair.nwAddr);
            assertEquals(pair.tpPort, pair.tpPort); // untranslated

            // Do the lookups now
            assertEquals(pair, natManager.lookupSnatFwd(
                ICMP.PROTOCOL_NUMBER, oldNwSrc, oldTpSrc, nwDst, tpDst));
            pair = new NwTpPair(oldNwSrc, oldTpSrc);
            assertEquals(pair, natManager.lookupSnatRev(
                ICMP.PROTOCOL_NUMBER, nat.nwStart, pair.tpPort, nwDst, tpDst));
        }
    }

    @Test
    public void testSnatOneTargetOneIpIPv4() {
        List<NatTarget> nats = new ArrayList<NatTarget>();
        nats.add(new NatTarget(0xc0a80109, 0xc0a80109,
                               (short) 1001, (short) 1001));
        nats.add(new NatTarget(0xc0a8010a, 0xc0a8010a,
                               (short) 1004, (short) 1004));
        nats.add(new NatTarget(0xc0a8010b, 0xc0a8010b,
                               (short) 100, (short) 223));
        nats.add(new NatTarget(0xc0a8010c, 0xc0a8010c,
                               (short) 300, (short) 600));
        testSnatOneTargetOneIp(nats, new IPv4Addr(0xc0a80101));
    }

    private void testSnatOneTargetOneIp(List<NatTarget> nats, IPAddr nwDst) {
        Set<NatTarget> natSet = new HashSet<>();
        int i = 0;
        short tpDst = 80;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            int collisions = 0;
            int numPorts = nat.tpEnd - nat.tpStart + 1;
            for (int j = 0; j < numPorts; j++) {
                IPv4Addr oldNwSrc = IPv4Addr.fromInt(0x0a000002 + (j % 8));
                short oldTpSrc = (short) (1000 * i + j);
                Assert.assertNull(natManager.lookupSnatFwd(TCP.PROTOCOL_NUMBER,
                                                           oldNwSrc, oldTpSrc,
                                                           nwDst, tpDst));
                NwTpPair pair = natManager.allocateSnat(TCP.PROTOCOL_NUMBER,
                                                        oldNwSrc, oldTpSrc,
                                                        nwDst, tpDst, natSet);
                if (pair == null) {
                    collisions++;
                    continue;
                }

                // We can verify the ip itself, bc there is only 1 in the target
                assertEquals(nat.nwStart, pair.nwAddr);
                assertTrue(nat.tpStart <= pair.tpPort);
                assertTrue(nat.tpEnd >= pair.tpPort);
                assertEquals(pair, natManager.lookupSnatFwd(
                    TCP.PROTOCOL_NUMBER, oldNwSrc, oldTpSrc, nwDst, tpDst));
                NwTpPair orig = new NwTpPair(oldNwSrc, oldTpSrc);
                assertEquals(orig, natManager.lookupSnatRev(
                    TCP.PROTOCOL_NUMBER, pair.nwAddr, pair.tpPort,
                    nwDst, tpDst));
            }

            // because random allocations, tolerate some collisions
            int allowedPercentage = 8;
            assertTrue(
                collisions + " collisions for " + numPorts + " allocations, " +
                "more than the allowed " + allowedPercentage + "%.",
                (100 * collisions) / numPorts < allowedPercentage);
        }
    }

    @Test
    public void testDnatOneTargetManyIpsOnePort() {
        List<NatTarget> nats = new ArrayList<NatTarget>();
        // One ip.
        nats.add(new NatTarget(0x0a0a0102, 0x0a0a0102, (short) 80,
                (short) 80));
        // Two ip's.
        nats.add(new NatTarget(0x0a0a0102, 0x0a0a0103, (short) 80,
                (short) 80));
        // More than 10 ip's.
        nats.add(new NatTarget(0x0a0a0102, 0x0a0a010f, (short) 80,
                (short) 80));
        nats.add(new NatTarget(0x0a000010, 0x0a00001f, (short) 11211,
                (short) 11211));
        Set<NatTarget> natSet = new HashSet<NatTarget>();
        int i = 0;
        // Assume there's only one public ip address for dnat.
        IPv4Addr nwDst = IPv4Addr.fromInt(0x80c00105);
        short tpDst = 80;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            // For unique external nwSrc+tpSrc there's no limit to how many
            // dnat mappings we can assign.
            for (int j = 0; j < 1000; j++) {
                IPv4Addr nwSrc = IPv4Addr.fromInt(0x8c000001 + j);
                short tpSrc = (short) (10000 * i + j);
                Assert.assertNull(natManager.lookupDnatFwd(TCP.PROTOCOL_NUMBER,
                                                           nwSrc, tpSrc,
                                                           nwDst, tpDst));
                NwTpPair pairF = natManager.allocateDnat(TCP.PROTOCOL_NUMBER,
                                                         nwSrc, tpSrc,
                                                         nwDst, tpDst, natSet);
                assertTrue(((IPv4Addr) nat.nwStart)
                               .$less$eq((IPv4Addr) pairF.nwAddr));
                assertTrue(((IPv4Addr) pairF.nwAddr)
                               .$less$eq((IPv4Addr) nat.nwEnd));
                assertEquals(nat.tpStart, pairF.tpPort);
                assertEquals(pairF, natManager.lookupDnatFwd(
                    TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst));
                NwTpPair pairR = new NwTpPair(nwDst, tpDst);
                assertEquals(pairR, natManager.lookupDnatRev(
                    TCP.PROTOCOL_NUMBER, nwSrc, tpSrc,
                    pairF.nwAddr, pairF.tpPort));
            }
        }
    }

    @Test
    public void testDnatTwoTargetsOneIpOnePortEach() {
        Set<NatTarget> natSet = new HashSet<NatTarget>();
        NatTarget nat1 = new NatTarget(0x0a0a0102, 0x0a0a0102, (short) 1000,
                (short) 1000);
        natSet.add(nat1);
        NatTarget nat2 = new NatTarget(0x0a0a0105, 0x0a0a0105, (short) 1001,
                (short) 1001);
        natSet.add(nat2);
        IPv4Addr nwDst = IPv4Addr.fromInt(0xd4c00a01);
        short tpDst = 80;
        for (int j = 0; j < 1000; j++) {
            IPv4Addr nwSrc = IPv4Addr.fromInt(0x8c000001 + j);
            short tpSrc = 12345;
            Assert.assertNull(natManager.lookupDnatFwd(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst));
            NwTpPair pairF = natManager.allocateDnat(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst, natSet);
            assertTrue(nat1.nwStart.equals(pairF.nwAddr) ||
                           nat2.nwStart.equals(pairF.nwAddr));
            assertTrue(nat1.tpStart == pairF.tpPort ||
                           nat2.tpStart == pairF.tpPort);
            assertEquals(pairF, natManager.lookupDnatFwd(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst));
            NwTpPair pairR = new NwTpPair(nwDst, tpDst);
            assertEquals(pairR, natManager.lookupDnatRev(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, pairF.nwAddr, pairF.tpPort));
        }
    }
    */
}
