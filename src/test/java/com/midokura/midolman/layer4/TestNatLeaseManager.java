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

import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.RouterDirectory.RouterConfig;
import com.midokura.midolman.util.MockCache;

public class TestNatLeaseManager {

    private NatLeaseManager natManager;

    @Before
    public void setUp() throws Exception {
        Directory dir = new MockDirectory();
        dir.add("/midonet", null, CreateMode.PERSISTENT);
        dir.add("/midonet/routers", null, CreateMode.PERSISTENT);
        Directory routersSubdir = dir.getSubDirectory("/midonet/routers");
        RouterDirectory routerDir = new RouterDirectory(routersSubdir);
        UUID rtrId = new UUID(1234, 5678);
        UUID tenantId = new UUID(1234, 6789);
        RouterConfig cfg = new RouterConfig("Test Router", tenantId);
        routerDir.addRouter(rtrId, cfg);
        natManager = new NatLeaseManager(routerDir, rtrId, new MockCache());
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
                        nwDst, tpDst));
                if (j == 1)
                    System.out.println("foooooo");
                NwTpPair pair = natManager.allocateSnat(oldNwSrc, oldTpSrc,
                        nwDst, tpDst, natSet);
                Assert.assertEquals(nat.nwStart, pair.nwAddr);
                Assert.assertEquals(nat.tpStart + j, pair.tpPort);
                Assert.assertTrue(pair.equals(natManager.lookupSnatFwd(
                        oldNwSrc, oldTpSrc, nwDst, tpDst)));
                pair = new NwTpPair(oldNwSrc, oldTpSrc);
                Assert.assertTrue(pair.equals(natManager.lookupSnatRev(
                        nat.nwStart, (short)(nat.tpStart + j), nwDst, tpDst)));
            }
            Assert.assertNull(natManager.allocateSnat(0x0a000001, (short) 43,
                    0xc0a80102, (short) 2182, natSet));
        }
    }
}
