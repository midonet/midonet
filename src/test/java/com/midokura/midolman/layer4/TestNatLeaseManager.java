package com.midokura.midolman.layer4;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.MockCache;

public class TestNatLeaseManager {

    private NatLeaseManager natManager;

    @Before
    public void setUp() throws Exception {
        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);
        Reactor reactor = new MockReactor();

        UUID rtrId = routerMgr.create();
        natManager = new NatLeaseManager(routerMgr, rtrId, new MockCache(),
                reactor);
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
        int nwDst = 0xc0a80101;
        short tpDst = 80;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            int numPorts = nat.tpEnd - nat.tpStart + 1;
            for (int j = 0; j < numPorts; j++) {
                int oldNwSrc = 0x0a000002 + (j % 8);
                short oldTpSrc = (short) (1000 * i + j);
                Assert.assertNull(natManager.lookupSnatFwd(oldNwSrc, oldTpSrc,
                        nwDst, tpDst, null));
                NwTpPair pair = natManager.allocateSnat(oldNwSrc, oldTpSrc,
                        nwDst, tpDst, natSet, new MidoMatch());
                Assert.assertEquals(nat.nwStart, pair.nwAddr);
                Assert.assertEquals(nat.tpStart + j, pair.tpPort);
                Assert.assertEquals(pair, natManager.lookupSnatFwd(
                        oldNwSrc, oldTpSrc, nwDst, tpDst, null));
                pair = new NwTpPair(oldNwSrc, oldTpSrc);
                Assert.assertEquals(pair, natManager.lookupSnatRev(
                        nat.nwStart, (short)(nat.tpStart + j), nwDst, tpDst));
            }
            Assert.assertNull(natManager.allocateSnat(0x0a000001, (short) 43,
                    0xc0a80102, (short) 2182, natSet, new MidoMatch()));
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
        int nwDst = 0x80c00105;
        short tpDst = 80;
        for (NatTarget nat : nats) {
            i++;
            natSet.clear();
            natSet.add(nat);
            // For unique external nwSrc+tpSrc there's no limit to how many
            // dnat mappings we can assign.
            for (int j = 0; j < 1000; j++) {
                int nwSrc = 0x8c000001 + j;
                short tpSrc = (short) (10000 * i + j);
                Assert.assertNull(natManager.lookupDnatFwd(nwSrc, tpSrc,
                        nwDst, tpDst, null));
                NwTpPair pairF = natManager.allocateDnat(nwSrc, tpSrc,
                        nwDst, tpDst, natSet, new MidoMatch());
                Assert.assertTrue(nat.nwStart <= pairF.nwAddr);
                Assert.assertTrue(pairF.nwAddr <= nat.nwEnd);
                Assert.assertEquals(nat.tpStart, pairF.tpPort);
                Assert.assertEquals(pairF, natManager.lookupDnatFwd(
                        nwSrc, tpSrc, nwDst, tpDst, null));
                NwTpPair pairR = new NwTpPair(nwDst, tpDst);
                Assert.assertEquals(pairR, natManager.lookupDnatRev(
                        nwSrc, tpSrc, pairF.nwAddr, pairF.tpPort));
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
        int nwDst = 0xd4c00a01;
        short tpDst = 80;
        for (int j = 0; j < 1000; j++) {
            int nwSrc = 0x8c000001 + j;
            short tpSrc = 12345;
            Assert.assertNull(natManager.lookupDnatFwd(nwSrc, tpSrc,
                    nwDst, tpDst, null));
            NwTpPair pairF = natManager.allocateDnat(nwSrc, tpSrc,
                    nwDst, tpDst, natSet, new MidoMatch());
            Assert.assertTrue(nat1.nwStart == pairF.nwAddr ||
                    nat2.nwStart == pairF.nwAddr);
            Assert.assertTrue(nat1.tpStart == pairF.tpPort ||
                    nat2.tpStart == pairF.tpPort);
            Assert.assertEquals(pairF, natManager.lookupDnatFwd(
                    nwSrc, tpSrc, nwDst, tpDst, null));
            NwTpPair pairR = new NwTpPair(nwDst, tpDst);
            Assert.assertEquals(pairR, natManager.lookupDnatRev(
                    nwSrc, tpSrc, pairF.nwAddr, pairF.tpPort));
        }
    }
}
