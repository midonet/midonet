/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;

public class VtepBrokerTest {

    private VtepBroker vtepBroker = null;

    private String logicalSwitchName = "ls";
    private IPv4Addr ip = IPv4Addr.fromString("119.15.113.90");
    private String sMac = "aa:bb:cc:dd:ee:01";
    private MAC mac = MAC.fromString(sMac);

    @Mocked
    private VtepDataClient vtepDataClient;

    @Before
    public void setUp() {
        vtepBroker = new VtepBroker(this.vtepDataClient);
    }

    @Test
    public void testUpdateHandlerAddsUcastMacRemote() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                logicalSwitchName, mac.toString(), ip.toString());
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};

        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, ip));
    }

    @Test(expected = VxLanPeerSyncException.class)
    public void testUpdateHandlerThrowsOnFailure() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                logicalSwitchName, mac.toString(), ip.toString());
            times = 1;
            result = new Status(StatusCode.BADREQUEST);
        }};

        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, ip));
    }

    @Test
    public void testUpdateHandlerDeletesUcastMacRemote() {
        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, null));
        // Port deletion is not handled at the moment.
        new Verifications() {{
            vtepDataClient.addUcastMacRemote(anyString, anyString, anyString);
            times = 0;
        }};
    }

    /**
     * This one will need a bit of refactoring, setting a value then changing
     * it and verifying the calls, whatever they do.
     */
    @Ignore
    @Test
    public void testUpdateHandlerUpdatesUcastMacRemote() {
        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, ip));
        // Port update is not handled at the moment.
        new Verifications() {{
            vtepDataClient.addUcastMacRemote(anyString, anyString, anyString);
            times = 0;
        }};
    }
}
