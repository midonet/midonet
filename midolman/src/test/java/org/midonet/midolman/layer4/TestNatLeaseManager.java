/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cache.Cache;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.ZkPathManager;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.util.MockCache;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.TCP;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

public class TestNatLeaseManager {

    private NatLeaseManager natManager;

    protected Cache createCache() {
        return new MockCache();
    }

    @Before
    public void setUp() throws Exception {
        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getFiltersPath(), null, CreateMode.PERSISTENT);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);
        Reactor reactor = new MockReactor();

        UUID rtrId = routerMgr.create();
        natManager = new NatLeaseManager(new FiltersZkManager(dir, basePath),
                rtrId, createCache(), reactor);
    }

    @Test
    public void testSnatPing() {
        List<NatTarget> nats = new ArrayList<NatTarget>();
        nats.add(new NatTarget(0xc0a8010b, 0xc0a8010b, (short) 100, (short) 100));
        nats.add(new NatTarget(0xc0a8010c, 0xc0a8010c, (short) 200, (short) 200));
        Set<NatTarget> natSet = new HashSet<NatTarget>();
        int i = 0;
        IPv4Addr nwDst = new IPv4Addr().setIntAddress(0xc0a80101);
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            IPv4Addr oldNwSrc = new IPv4Addr().setIntAddress(0x0a000002);
            short oldTpSrc = (short) (10 * i); // this would be icmp identifier
            short tpDst = oldTpSrc;            // in ICMP the port is redundant
            Assert.assertNull(natManager.lookupSnatFwd(ICMP.PROTOCOL_NUMBER,
                                                       oldNwSrc, oldTpSrc,
                                                       nwDst, tpDst));
            NwTpPair pair = natManager.allocateSnat(ICMP.PROTOCOL_NUMBER,
                                                    oldNwSrc, oldTpSrc,
                                                    nwDst, tpDst, natSet);
            Assert.assertEquals(new IPv4Addr().setIntAddress(nat.nwStart),
                                pair.nwAddr);
            Assert.assertEquals(pair.tpPort, pair.tpPort); // untranslated

            // Do the lookups now
            Assert.assertEquals(pair, natManager.lookupSnatFwd(
                ICMP.PROTOCOL_NUMBER, oldNwSrc, oldTpSrc, nwDst, tpDst));
            pair = new NwTpPair(oldNwSrc, oldTpSrc);
            Assert.assertEquals(pair, natManager.lookupSnatRev(
                ICMP.PROTOCOL_NUMBER, new IPv4Addr().setIntAddress(nat.nwStart),
                pair.tpPort, nwDst, tpDst));
        }
    }

    @Test
    public void testSnatOneTargetOneIp() {
        List<NatTarget> nats = new ArrayList<NatTarget>();
        nats.add(new NatTarget(0xc0a80109, 0xc0a80109, (short) 1001,
                (short) 1001));
        nats.add(new NatTarget(0xc0a8010a, 0xc0a8010a, (short) 1004,
                (short) 1004));
        nats.add(new NatTarget(0xc0a8010b, 0xc0a8010b, (short) 100, (short) 223));
        nats.add(new NatTarget(0xc0a8010c, 0xc0a8010c, (short) 300, (short) 600));
        Set<NatTarget> natSet = new HashSet<NatTarget>();
        int i = 0;
        IPv4Addr nwDst = new IPv4Addr().setIntAddress(0xc0a80101);
        short tpDst = 80;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            int numPorts = nat.tpEnd - nat.tpStart + 1;
            for (int j = 0; j < numPorts; j++) {
                IPv4Addr oldNwSrc = new IPv4Addr().setIntAddress(
                        0x0a000002 + (j % 8));
                short oldTpSrc = (short) (1000 * i + j);
                Assert.assertNull(natManager.lookupSnatFwd(TCP.PROTOCOL_NUMBER,
                                                           oldNwSrc, oldTpSrc,
                                                           nwDst, tpDst));
                NwTpPair pair = natManager.allocateSnat(TCP.PROTOCOL_NUMBER,
                                                        oldNwSrc, oldTpSrc,
                                                        nwDst, tpDst, natSet);
                Assert.assertEquals(new IPv4Addr().setIntAddress(nat.nwStart),
                                    pair.nwAddr);
                Assert.assertEquals(nat.tpStart + j, pair.tpPort);
                Assert.assertEquals(pair, natManager.lookupSnatFwd(
                        TCP.PROTOCOL_NUMBER, oldNwSrc, oldTpSrc, nwDst, tpDst));
                pair = new NwTpPair(oldNwSrc, oldTpSrc);
                Assert.assertEquals(pair, natManager.lookupSnatRev(
                    TCP.PROTOCOL_NUMBER,
                    new IPv4Addr().setIntAddress(nat.nwStart),
                    (short)(nat.tpStart + j), nwDst, tpDst));
            }
            Assert.assertNull(natManager.allocateSnat(TCP.PROTOCOL_NUMBER,
                    new IPv4Addr().setIntAddress(0x0a000001), (short) 43,
                    new IPv4Addr().setIntAddress(0xc0a80102), (short) 2182,
                    natSet));
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
        IPv4Addr nwDst = new IPv4Addr().setIntAddress(0x80c00105);
        short tpDst = 80;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            // For unique external nwSrc+tpSrc there's no limit to how many
            // dnat mappings we can assign.
            for (int j = 0; j < 1000; j++) {
                IPv4Addr nwSrc = new IPv4Addr().setIntAddress(0x8c000001 + j);
                short tpSrc = (short) (10000 * i + j);
                Assert.assertNull(natManager.lookupDnatFwd(TCP.PROTOCOL_NUMBER,
                                                           nwSrc, tpSrc,
                                                           nwDst, tpDst));
                NwTpPair pairF = natManager.allocateDnat(TCP.PROTOCOL_NUMBER,
                                                         nwSrc, tpSrc,
                                                         nwDst, tpDst, natSet);
                Assert.assertTrue(nat.nwStart <=
                                      ((IPv4Addr)(pairF.nwAddr)).getIntAddress());
                Assert.assertTrue(((IPv4Addr)(pairF.nwAddr)).getIntAddress() <=
                                      nat.nwEnd);
                Assert.assertEquals(nat.tpStart, pairF.tpPort);
                Assert.assertEquals(pairF, natManager.lookupDnatFwd(
                    TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst));
                NwTpPair pairR = new NwTpPair(nwDst, tpDst);
                Assert.assertEquals(pairR, natManager.lookupDnatRev(
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
        IPv4Addr nwDst = new IPv4Addr().setIntAddress(0xd4c00a01);
        short tpDst = 80;
        for (int j = 0; j < 1000; j++) {
            IPv4Addr nwSrc = new IPv4Addr().setIntAddress(0x8c000001 + j);
            short tpSrc = 12345;
            Assert.assertNull(natManager.lookupDnatFwd(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst));
            NwTpPair pairF = natManager.allocateDnat(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst, natSet);
            IPAddr nwStart1 = new IPv4Addr().setIntAddress(nat1.nwStart);
            IPAddr nwStart2 = new IPv4Addr().setIntAddress(nat2.nwStart);
            Assert.assertTrue(nwStart1.equals(pairF.nwAddr) ||
                    nwStart2.equals(pairF.nwAddr));
            Assert.assertTrue(nat1.tpStart == pairF.tpPort ||
                    nat2.tpStart == pairF.tpPort);
            Assert.assertEquals(pairF, natManager.lookupDnatFwd(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, nwDst, tpDst));
            NwTpPair pairR = new NwTpPair(nwDst, tpDst);
            Assert.assertEquals(pairR, natManager.lookupDnatRev(
                TCP.PROTOCOL_NUMBER, nwSrc, tpSrc, pairF.nwAddr, pairF.tpPort));
        }
    }
}
